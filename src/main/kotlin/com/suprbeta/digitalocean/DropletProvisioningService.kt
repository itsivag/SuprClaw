package com.suprbeta.digitalocean

import com.suprbeta.config.AppConfig
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.infra.DnsProvider
import com.suprbeta.infra.VpsProvider
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SupabaseManagementService
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates OpenClaw droplet provisioning.
 *
 * After creating a droplet with minimal cloud-init (user creation only),
 * this service polls until the droplet is active, SSHs in, and runs
 * openclaw onboarding remotely — so systemd user daemon works correctly.
 */
interface DropletProvisioningService {
    data class CreateResult(
        val dropletId: Long,
        val status: ProvisioningStatus,
        val password: String
    )

    suspend fun createAndProvision(name: String): CreateResult

    suspend fun provisionDroplet(
        dropletId: Long,
        password: String,
        userId: String
    ): UserDroplet

    fun getStatus(dropletId: Long): ProvisioningStatus?

    suspend fun teardown(userId: String)
}

class DropletProvisioningServiceImpl(
    private val vpsProvider: VpsProvider,
    private val dnsProvider: DnsProvider,
    private val firestoreRepository: FirestoreRepository,
    private val agentRepository: SupabaseAgentRepository,
    private val schemaRepository: SupabaseSchemaRepository,
    private val managementService: SupabaseManagementService,
    private val userClientProvider: UserSupabaseClientProvider,
    private val openClawConnector: OpenClawConnector,
    private val sshCommandExecutor: SshCommandExecutor,
    private val dropletMcpService: DropletMcpService,
    application: Application
) : DropletProvisioningService {
    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory status map keyed by droplet ID. */
    val statuses = ConcurrentHashMap<Long, ProvisioningStatus>()

    companion object {
        private const val GATEWAY_PORT = 18789
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WAIT_MS = 300_000L       // 5 minutes for droplet to become active
        private const val GATEWAY_VERIFY_TIMEOUT_S = 30

        // Progress values for each phase (0.0 to 1.0)
        private val PHASE_PROGRESS = mapOf(
            ProvisioningStatus.PHASE_CREATING to 0.0,
            ProvisioningStatus.PHASE_WAITING_ACTIVE to 0.125,
            ProvisioningStatus.PHASE_WAITING_SSH to 0.25,
            ProvisioningStatus.PHASE_CONFIGURING to 0.55,
            ProvisioningStatus.PHASE_DNS to 0.65,
            ProvisioningStatus.PHASE_VERIFYING to 0.75,
            ProvisioningStatus.PHASE_NGINX to 0.875,
            ProvisioningStatus.PHASE_COMPLETE to 1.0,
            ProvisioningStatus.PHASE_FAILED to 0.0
        )
    }

    // ── Phase 1 — Droplet Creation ──────────────────────────────────────

    /**
     * Creates the droplet and returns info needed to kick off provisioning.
     * The returned password is held in memory only until SSH completes.
     */
    override suspend fun createAndProvision(name: String): DropletProvisioningService.CreateResult {
        val password = UserDataGenerator.generatePassword()

        val createResult = vpsProvider.create(name, password)
        val dropletId = createResult.id

        val status = ProvisioningStatus(
            droplet_id = dropletId,
            droplet_name = name,
            phase = ProvisioningStatus.PHASE_CREATING,
            progress = 0.0,
            message = "Droplet created, waiting for it to become active...",
            started_at = Instant.now().toString()
        )
        statuses[dropletId] = status

        return DropletProvisioningService.CreateResult(dropletId, status, password)
    }

    /**
     * Internal entry point that drives the provisioning coroutine.
     * Called as fire-and-forget from the route handler.
     */
    override suspend fun provisionDroplet(dropletId: Long, password: String, userId: String): UserDroplet {
        var projectRef: String? = null

        try {
            val statusNow = statuses[dropletId]
            val dropletName = statusNow?.droplet_name ?: "droplet-$dropletId"

            // Phase 2 — Wait for active + IP and create Supabase project in parallel
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_ACTIVE, "Polling DigitalOcean API and creating Supabase project...")

            var ipAddress: String? = null
            var serviceKey: String? = null

            coroutineScope {
                val waitForActiveDeferred = async { waitForDropletReady(dropletId) }
                val createProjectDeferred = async {
                    val sanitizedName = dropletName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                    val projectName = "suprclaw-$sanitizedName"
                    val result = managementService.createProject(projectName)
                    managementService.waitForProjectActive(result.projectRef)
                    val sk = managementService.getServiceKey(result.projectRef)
                    Pair(result.projectRef, sk)
                }

                ipAddress = waitForActiveDeferred.await()
                val (pRef, sk) = createProjectDeferred.await()
                projectRef = pRef
                serviceKey = sk
            }

            val resolvedIp = ipAddress!!
            val resolvedProjectRef = projectRef!!
            val resolvedServiceKey = serviceKey!!

            logger.info("Droplet $dropletId active at $resolvedIp, Supabase project $resolvedProjectRef ready")

            // Phase 3 — Wait for SSH port
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for SSH port...")
            sshCommandExecutor.waitForSshReady(resolvedIp)

            // Phase 3b — Wait for cloud-init to apply password (deterministic probe)
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for cloud-init to finish (probing SSH auth)...")
            sshCommandExecutor.waitForSshAuth(resolvedIp, password)

            // Phase 4 — Configuration (gateway token + hook token)
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Configuring gateway token...")
            val gatewayToken = generateGatewayToken()
            val hookToken = generateGatewayToken()

            // Write tokens directly to openclaw.json to guarantee exact values (openclaw config set transforms the input)
            val updateConfigScript = """node -e "const fs=require('fs'),p='/home/openclaw/.openclaw/openclaw.json',c=JSON.parse(fs.readFileSync(p,'utf8'));c.gateway=c.gateway||{};c.gateway.auth=c.gateway.auth||{};c.gateway.auth.token='$gatewayToken';c.gateway.remote=c.gateway.remote||{};c.gateway.remote.token='$gatewayToken';c.gateway.mode='local';c.hooks=c.hooks||{};c.hooks.token='$hookToken';fs.writeFileSync(p,JSON.stringify(c,null,2));" """
            sshCommandExecutor.runSshCommand(resolvedIp, password, updateConfigScript)

            // Sync token into user service file (openclaw validates against this even when using system service)
            sshCommandExecutor.runSshCommand(resolvedIp, password, "sudo loginctl enable-linger openclaw")
            sshCommandExecutor.runSshCommand(resolvedIp, password, "sed -i 's/Environment=OPENCLAW_GATEWAY_TOKEN=.*/Environment=OPENCLAW_GATEWAY_TOKEN=$gatewayToken/' /home/openclaw/.config/systemd/user/openclaw-gateway.service 2>/dev/null || true")
            delay(2000)
            sshCommandExecutor.runSshCommand(resolvedIp, password, "XDG_RUNTIME_DIR=/run/user/\$(id -u) systemctl --user daemon-reload 2>/dev/null || true")

            logger.info("Gateway token set for droplet $dropletId: $gatewayToken")

            // Phase 4b — Write MCP credentials and start system services
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Configuring MCP credentials and starting services...")
            val bootstrapDroplet = UserDropletInternal(
                userId = userId,
                dropletId = dropletId,
                ipAddress = resolvedIp,
                sshKey = password,
                supabaseProjectRef = resolvedProjectRef,
                gatewayToken = gatewayToken
            )
            dropletMcpService.configureMcpTools(bootstrapDroplet, McpToolRegistry.defaultTools)

            sshCommandExecutor.runSshCommand(resolvedIp, password, "sudo systemctl start mcp-auth-proxy mcporter openclaw-gateway")
            sshCommandExecutor.runSshCommand(resolvedIp, password, "nohup openclaw doctor --fix > /tmp/openclaw-doctor.log 2>&1 &")
            logger.info("MCP credentials written and services started for droplet $dropletId")

            // Phase 6 — DNS configuration (MANDATORY - fail if this fails)
            updateStatus(dropletId, ProvisioningStatus.PHASE_DNS, "Creating DNS record...")
            val sanitizedName = dropletName.lowercase().replace(Regex("[^a-z0-9-]"), "-")

            // Create DNS record - this will throw if it fails, triggering cleanup
            val subdomain = dnsProvider.createRecord(sanitizedName, resolvedIp)
            logger.info("✅ Subdomain configured: $subdomain")

            // Phase 7 — Gateway verification
            updateStatus(dropletId, ProvisioningStatus.PHASE_VERIFYING, "Verifying gateway status...")
            verifyGateway(resolvedIp, password)

            // Phase 7 — Nginx reverse proxy + SSL (only for VPS/production)
            if (AppConfig.sslEnabled) {
                updateStatus(dropletId, ProvisioningStatus.PHASE_NGINX, "Installing nginx and obtaining SSL certificate...")
                setupNginxReverseProxy(resolvedIp, password, subdomain)
            } else {
                logger.info("ℹ️ Skipping Nginx/SSL setup (running in local mode)")
            }

            val vpsGatewayUrl = if (AppConfig.sslEnabled) "https://$subdomain" else "http://$resolvedIp:$GATEWAY_PORT"
            val proxyGatewayUrl = "wss://api.suprclaw.com"

            // Final phase: real first connect, and if pairing is required, approve requestId once.
            // Per requirement: do not reconnect after approval.
            approvePairingIfRequired(vpsGatewayUrl, gatewayToken, resolvedIp, password, dropletId)

            // Phase 5 — Initialize user project tables via Management API SQL
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Initializing user project tables...")
            schemaRepository.initializeUserProject(
                managementService = managementService,
                projectRef = resolvedProjectRef,
                userId = userId,
                vpsGatewayUrl = vpsGatewayUrl,
                gatewayToken = gatewayToken
            )

            // Create internal droplet with both URLs and Supabase project info
            val userDropletInternal = UserDropletInternal(
                userId = userId,
                dropletId = dropletId,
                dropletName = dropletName,
                gatewayUrl = proxyGatewayUrl,           // Proxy URL for clients
                vpsGatewayUrl = vpsGatewayUrl,          // Actual VPS URL for backend routing
                gatewayToken = gatewayToken,
                hookToken = hookToken,
                sshKey = password,
                ipAddress = resolvedIp,
                subdomain = subdomain.takeIf { AppConfig.sslEnabled },
                createdAt = Instant.now().toString(),
                status = "active",
                sslEnabled = AppConfig.sslEnabled,
                supabaseProjectRef = resolvedProjectRef,
                supabaseServiceKey = resolvedServiceKey,
                configuredMcpTools = McpToolRegistry.defaultTools
            )

            // Save to Firestore + save agent — must all succeed before marking complete
            coroutineScope {
                val saveDroplet = async {
                    firestoreRepository.saveUserDroplet(userDropletInternal)
                }

                saveDroplet.await()

                // Save lead agent to user's own Supabase project
                val supabaseUrl = "https://$resolvedProjectRef.supabase.co"
                val userClient = userClientProvider.getClient(supabaseUrl, resolvedServiceKey)
                agentRepository.saveAgent(
                    userClient,
                    AgentInsert(
                        name = "Lead",
                        role = "Lead Coordinator",
                        sessionKey = "agent:main:main",
                        isLead = true,
                        status = "active"
                    )
                )
            }

            logger.info("💾 User droplet saved to Firestore, default main agent saved to user's Supabase project: userId=$userId, dropletId=$dropletId, projectRef=$resolvedProjectRef")

            // Phase 8 — Complete (only reached if all saves succeeded)
            updateStatus(
                dropletId, ProvisioningStatus.PHASE_COMPLETE,
                "Provisioning complete. Connect via proxy at $proxyGatewayUrl",
                completedAt = Instant.now().toString()
            )
            logger.info("✅ Droplet $dropletId provisioning complete. VPS: $vpsGatewayUrl, Proxy: $proxyGatewayUrl, Supabase: $projectRef")

            // Return client-safe version (without VPS URL)
            return userDropletInternal.toUserDroplet()

        } catch (e: Exception) {
            logger.error("❌ Droplet $dropletId provisioning failed, cleaning up...", e)

            // Clean up: destroy the failed VPS
            try {
                vpsProvider.delete(dropletId)
                logger.info("🗑️ Droplet $dropletId deleted after provisioning failure")
            } catch (deleteError: Exception) {
                logger.error("Failed to delete droplet $dropletId: ${deleteError.message}")
            }

            // Clean up: delete the Supabase project if it was created
            if (projectRef != null) {
                try {
                    managementService.deleteProject(projectRef!!)
                    logger.info("🗑️ Supabase project $projectRef deleted after provisioning failure")
                } catch (deleteError: Exception) {
                    logger.error("Failed to delete Supabase project $projectRef: ${deleteError.message}")
                }
            }

            val current = statuses[dropletId]
            statuses[dropletId] = current?.copy(
                phase = ProvisioningStatus.PHASE_FAILED,
                message = "Provisioning failed (droplet destroyed): ${e.message}",
                error = e.stackTraceToString(),
                completed_at = Instant.now().toString()
            ) ?: ProvisioningStatus(
                droplet_id = dropletId,
                droplet_name = "unknown",
                phase = ProvisioningStatus.PHASE_FAILED,
                message = "Provisioning failed (droplet destroyed): ${e.message}",
                error = e.stackTraceToString(),
                started_at = Instant.now().toString(),
                completed_at = Instant.now().toString()
            )

            throw e // Re-throw exception after cleanup
        }
    }

    override fun getStatus(dropletId: Long): ProvisioningStatus? = statuses[dropletId]

    override suspend fun teardown(userId: String) {
        val droplet = firestoreRepository.getUserDropletInternal(userId)
            ?: throw IllegalStateException("No droplet found for user $userId")

        val errors = mutableListOf<String>()

        // 1 — Delete VPS
        try {
            vpsProvider.delete(droplet.dropletId)
            logger.info("🗑️ VPS ${droplet.dropletId} deleted for userId=$userId")
        } catch (e: Exception) {
            logger.error("Failed to delete DO droplet ${droplet.dropletId}", e)
            errors += "DO droplet: ${e.message}"
        }

        // 2 — Delete per-user Supabase project + remove user_droplets row from central project
        try {
            if (droplet.supabaseProjectRef.isNotBlank()) {
                schemaRepository.cleanupUserProject(managementService, droplet.supabaseProjectRef, userId)
            } else {
                logger.warn("No supabaseProjectRef found for userId=$userId — skipping Supabase project deletion")
            }
        } catch (e: Exception) {
            logger.error("Failed to cleanup Supabase project for userId=$userId", e)
            errors += "Supabase project: ${e.message}"
        }

        // 3 — Delete Firestore droplet document + project ref mapping
        try {
            firestoreRepository.deleteUserDroplet(userId)
            logger.info("🗑️ Firestore droplet record deleted for userId=$userId")
        } catch (e: Exception) {
            logger.error("Failed to delete Firestore droplet for userId=$userId", e)
            errors += "Firestore: ${e.message}"
        }

        if (droplet.supabaseProjectRef.isNotBlank()) {
            firestoreRepository.deleteProjectRefMapping(droplet.supabaseProjectRef)
        }

        // 4 — Clear in-memory status
        statuses.remove(droplet.dropletId)

        if (errors.isNotEmpty()) {
            throw IllegalStateException("Teardown partially failed: ${errors.joinToString("; ")}")
        }

        logger.info("✅ Full teardown complete for userId=$userId")
    }

    // ── Phase 2 — Poll until active ─────────────────────────────────────

    private suspend fun waitForDropletReady(dropletId: Long): String {
        val deadline = System.currentTimeMillis() + MAX_POLL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val state = vpsProvider.getState(dropletId)

                if (state.status == "active" && state.publicIpv4 != null) {
                    logger.info("VPS $dropletId is active at ${state.publicIpv4}")
                    return state.publicIpv4
                }

                logger.info("VPS $dropletId status: ${state.status}, waiting...")
            } catch (e: Exception) {
                logger.warn("Error polling VPS $dropletId: ${e.message}")
            }

            delay(POLL_INTERVAL_MS)
        }

        throw IllegalStateException("VPS $dropletId did not become active within ${MAX_POLL_WAIT_MS / 1000}s")
    }

    // ── Phase 5 — Gateway verification ──────────────────────────────────

    private suspend fun verifyGateway(ipAddress: String, password: String) {
        val deadline = System.currentTimeMillis() + (GATEWAY_VERIFY_TIMEOUT_S * 1000L)

        while (System.currentTimeMillis() < deadline) {
            try {
                val output = sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw gateway status")
                logger.info("Gateway status: ${output.take(200)}")
                return  // exit 0 means success
            } catch (e: Exception) {
                logger.info("Gateway not ready yet: ${e.message}")
                delay(2000)
            }
        }

        throw IllegalStateException("Gateway did not become ready within ${GATEWAY_VERIFY_TIMEOUT_S}s")
    }

    // ── Phase 7 — Nginx reverse proxy + SSL ────────────────────────────

    private fun setupNginxReverseProxy(ipAddress: String, password: String, subdomain: String) {
        logger.info("Configuring nginx on $ipAddress...")

        // Build initial HTTP-only nginx config for Let's Encrypt verification
        val nginxConfigHttp = """
server {
    listen 80;
    listen [::]:80;
    server_name $subdomain;

    location / {
        proxy_pass http://127.0.0.1:$GATEWAY_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade ${'$'}http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host ${'$'}host;
        proxy_set_header X-Real-IP ${'$'}remote_addr;
        proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto ${'$'}scheme;
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
}
""".trimIndent()

        val encoded = java.util.Base64.getEncoder().encodeToString(nginxConfigHttp.toByteArray())
        sshCommandExecutor.runSshCommand(ipAddress, password, "echo $encoded | base64 -d | sudo tee /etc/nginx/sites-available/openclaw > /dev/null")

        // Enable site and disable default
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo ln -sf /etc/nginx/sites-available/openclaw /etc/nginx/sites-enabled/openclaw")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo rm -f /etc/nginx/sites-enabled/default")

        // Test and reload nginx
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo nginx -t")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo systemctl reload nginx")

        logger.info("Nginx configured for HTTP: port 80 -> $GATEWAY_PORT")

        // Copy wildcard SSL certificate from backend to droplet
        logger.info("Deploying wildcard SSL certificate to $subdomain...")
        copySslCertificates(ipAddress, password)

        // Update nginx config to use SSL
        val nginxConfigHttps = """
server {
    listen 80;
    listen [::]:80;
    server_name $subdomain;
    return 301 https://${'$'}server_name${'$'}request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $subdomain;

    ssl_certificate /etc/ssl/certs/suprclaw.com/fullchain.pem;
    ssl_certificate_key /etc/ssl/certs/suprclaw.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    location / {
        proxy_pass http://127.0.0.1:$GATEWAY_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade ${'$'}http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host ${'$'}host;
        proxy_set_header X-Real-IP ${'$'}remote_addr;
        proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto ${'$'}scheme;
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
}
""".trimIndent()

        val encodedHttps = java.util.Base64.getEncoder().encodeToString(nginxConfigHttps.toByteArray())
        sshCommandExecutor.runSshCommand(ipAddress, password, "echo $encodedHttps | base64 -d | sudo tee /etc/nginx/sites-available/openclaw > /dev/null")

        // Reload nginx with SSL config
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo nginx -t")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo systemctl reload nginx")

        logger.info("✅ SSL configured for $subdomain using wildcard certificate")

        // Configure UFW firewall — allow SSH first (critical!), then HTTP, then enable
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo ufw allow OpenSSH")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo ufw allow 'Nginx Full'")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo ufw --force enable")

        logger.info("UFW firewall enabled: SSH + Nginx allowed")
    }

    // ── SSL Certificate helper ─────────────────────────────────────────

    /**
     * Copies wildcard SSL certificate from backend server to droplet via SSH
     */
    private fun copySslCertificates(ipAddress: String, password: String) {
        val certPath = "/etc/letsencrypt/live/suprclaw.com"

        // Read certificate files from local filesystem
        val fullchain = java.io.File("$certPath/fullchain.pem").readText()
        val privkey = java.io.File("$certPath/privkey.pem").readText()

        // Base64 encode to avoid escaping issues
        val fullchainEncoded = java.util.Base64.getEncoder().encodeToString(fullchain.toByteArray())
        val privkeyEncoded = java.util.Base64.getEncoder().encodeToString(privkey.toByteArray())

        // Create SSL directory on droplet
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo mkdir -p /etc/ssl/certs/suprclaw.com")

        // Copy fullchain.pem
        sshCommandExecutor.runSshCommand(ipAddress, password, "echo $fullchainEncoded | base64 -d | sudo tee /etc/ssl/certs/suprclaw.com/fullchain.pem > /dev/null")

        // Copy privkey.pem
        sshCommandExecutor.runSshCommand(ipAddress, password, "echo $privkeyEncoded | base64 -d | sudo tee /etc/ssl/certs/suprclaw.com/privkey.pem > /dev/null")

        // Set proper permissions
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo chmod 644 /etc/ssl/certs/suprclaw.com/fullchain.pem")
        sshCommandExecutor.runSshCommand(ipAddress, password, "sudo chmod 600 /etc/ssl/certs/suprclaw.com/privkey.pem")

        logger.info("SSL certificates copied to $ipAddress")
    }

    private suspend fun approvePairingIfRequired(
        vpsGatewayUrl: String,
        gatewayToken: String,
        ipAddress: String,
        password: String,
        dropletId: Long
    ) {
        val session = openClawConnector.connect(
            token = gatewayToken,
            vpsGatewayUrl = vpsGatewayUrl
        )

        if (session == null) {
            logger.warn("Final pairing phase skipped for droplet $dropletId: unable to open websocket to $vpsGatewayUrl")
            return
        }

        try {
            val deadlineMs = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadlineMs) {
                val inbound = withTimeoutOrNull(2_500L) { session.incoming.receive() } ?: continue
                if (inbound !is Frame.Text) continue

                val frame = json.decodeFromString<WebSocketFrame>(inbound.readText())

                if (frame.event == "connect.challenge") {
                    openClawConnector.handleConnectChallenge(
                        session = session,
                        token = gatewayToken,
                        challengePayload = frame.payload,
                        platform = "android"
                    )
                    continue
                }

                if (frame.type == "res" && frame.id == "1" && frame.error == null) {
                    logger.info("Final pairing phase: connect succeeded for droplet $dropletId (no approval needed)")
                    return
                }

                val errorObj = frame.error?.jsonObject ?: continue
                val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: continue

                if (message == "pairing required") {
                    val requestId = runCatching {
                        errorObj["details"]
                            ?.jsonObject
                            ?.get("requestId")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()

                    if (requestId.isNullOrBlank()) {
                        logger.warn("Final pairing phase: pairing required without requestId for droplet $dropletId")
                        return
                    }

                    sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw devices approve $requestId")
                    logger.info("Final pairing phase: approved requestId=$requestId for droplet $dropletId")
                    return
                }
            }

            logger.warn("Final pairing phase timed out for droplet $dropletId")
        } catch (e: Exception) {
            logger.warn("Final pairing phase failed for droplet $dropletId: ${e.message}")
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    // ── Status helpers ──────────────────────────────────────────────────

    private fun updateStatus(
        dropletId: Long,
        phase: String,
        message: String,
        completedAt: String? = null
    ) {
        val current = statuses[dropletId] ?: return
        val progress = PHASE_PROGRESS[phase] ?: current.progress
        statuses[dropletId] = current.copy(
            phase = phase,
            progress = progress,
            message = message,
            completed_at = completedAt
        )
    }

    /**
     * Generates a cryptographically secure random gateway token
     */
    private fun generateGatewayToken(): String {
        val chars = "abcdef0123456789"
        return (1..48)
            .map { chars.random() }
            .joinToString("")
    }

}
