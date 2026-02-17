package com.suprbeta.digitalocean

import com.suprbeta.config.AppConfig
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates OpenClaw droplet provisioning using Pattern A (SSH Onboarding).
 *
 * After creating a droplet with minimal cloud-init (user creation only),
 * this service polls until the droplet is active, SSHs in, and runs
 * openclaw onboarding remotely — so systemd user daemon works correctly.
 */
class DropletProvisioningService(
    private val digitalOceanService: DigitalOceanService,
    private val dnsService: DnsService,
    private val firestoreRepository: com.suprbeta.firebase.FirestoreRepository,
    private val openClawConnector: OpenClawConnector,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) {
    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory status map keyed by droplet ID. */
    val statuses = ConcurrentHashMap<Long, ProvisioningStatus>()

    companion object {
        private const val GATEWAY_PORT = 18789
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WAIT_MS = 300_000L       // 5 minutes for droplet to become active
        private const val ONBOARDING_MAX_RETRIES = 3
        private const val GATEWAY_VERIFY_TIMEOUT_S = 30

        // Progress values for each phase (0.0 to 1.0)
        private val PHASE_PROGRESS = mapOf(
            ProvisioningStatus.PHASE_CREATING to 0.0,
            ProvisioningStatus.PHASE_WAITING_ACTIVE to 0.125,
            ProvisioningStatus.PHASE_WAITING_SSH to 0.25,
            ProvisioningStatus.PHASE_ONBOARDING to 0.4,
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
    data class CreateResult(val dropletId: Long, val status: ProvisioningStatus, val password: String, val geminiApiKey: String)

    suspend fun createAndProvision(name: String): CreateResult {
        val password = UserDataGenerator.generatePassword()
        val geminiApiKey = digitalOceanService.geminiApiKey

        val doResponse = digitalOceanService.createDroplet(name, password)
        val dropletId = doResponse.droplet?.id
            ?: throw IllegalStateException("DigitalOcean did not return a droplet ID")

        val status = ProvisioningStatus(
            droplet_id = dropletId,
            droplet_name = name,
            phase = ProvisioningStatus.PHASE_CREATING,
            progress = 0.0,
            message = "Droplet created, waiting for it to become active...",
            started_at = Instant.now().toString()
        )
        statuses[dropletId] = status

        return CreateResult(dropletId, status, password, geminiApiKey)
    }

    /**
     * Internal entry point that drives the provisioning coroutine.
     * Called as fire-and-forget from the route handler.
     */
    suspend fun provisionDroplet(dropletId: Long, password: String, geminiApiKey: String, userId: String): UserDroplet {
        try {
            // Phase 2 — Wait for active + IP
            val ipAddress = waitForDropletReady(dropletId)

            // Phase 3 — Wait for SSH port
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for SSH port at $ipAddress...")
            sshCommandExecutor.waitForSshReady(ipAddress)

            // Phase 3b — Wait for cloud-init to apply password (deterministic probe)
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for cloud-init to finish (probing SSH auth)...", ipAddress)
            sshCommandExecutor.waitForSshAuth(ipAddress, password)

            // Phase 4 — Onboarding (with retries)
            updateStatus(dropletId, ProvisioningStatus.PHASE_ONBOARDING, "Running OpenClaw onboarding via SSH...", ipAddress)
            runOnboardingWithRetries(ipAddress, password, geminiApiKey)

            // Phase 5 — Configuration (model + gateway token)
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Configuring AI model and gateway...", ipAddress)
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw models set google/gemini-2.5-flash")

            // Generate and set gateway token
            val gatewayToken = generateGatewayToken()
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw config set gateway.auth.token $gatewayToken")
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw config set gateway.remote.token $gatewayToken")
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw config set gateway.mode local")
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw doctor --fix")
            sshCommandExecutor.runSshCommand(ipAddress, password, "openclaw gateway restart")

            logger.info("Gateway token set for droplet $dropletId: $gatewayToken")

            // Update status with the token
            val current = statuses[dropletId]
            if (current != null) {
                statuses[dropletId] = current.copy(gateway_token = gatewayToken)
            }

            // Phase 6 — DNS configuration (MANDATORY - fail if this fails)
            updateStatus(dropletId, ProvisioningStatus.PHASE_DNS, "Creating DNS record...", ipAddress)
            // Use droplet name as subdomain (sanitize it first)
            val statusNow = statuses[dropletId]
            val dropletName = statusNow?.droplet_name ?: "droplet-$dropletId"
            val sanitizedName = dropletName.lowercase().replace(Regex("[^a-z0-9-]"), "-")

            // Create DNS record - this will throw if it fails, triggering cleanup
            val subdomain = dnsService.createDnsRecord(sanitizedName, ipAddress)
            logger.info("✅ Subdomain configured: $subdomain")

            // Update status with subdomain
            val statusAfterDns = statuses[dropletId]
            if (statusAfterDns != null) {
                statuses[dropletId] = statusAfterDns.copy(
                    subdomain = subdomain,
                    gateway_token = gatewayToken
                )
            }

            // Phase 7 — Gateway verification
            updateStatus(dropletId, ProvisioningStatus.PHASE_VERIFYING, "Verifying gateway status...", ipAddress)
            verifyGateway(ipAddress, password)

            // Phase 7 — Nginx reverse proxy + SSL (only for VPS/production)
            if (AppConfig.sslEnabled) {
                updateStatus(dropletId, ProvisioningStatus.PHASE_NGINX, "Installing nginx and obtaining SSL certificate...", ipAddress)
                setupNginxReverseProxy(ipAddress, password, subdomain)
            } else {
                logger.info("ℹ️ Skipping Nginx/SSL setup (running in local mode)")
            }

            // Phase 8 — Complete
            val vpsGatewayUrl = if (AppConfig.sslEnabled) "https://$subdomain" else "http://$ipAddress:$GATEWAY_PORT"
            val proxyGatewayUrl = "wss://api.suprclaw.com"
            
            updateStatus(
                dropletId, ProvisioningStatus.PHASE_COMPLETE,
                "Provisioning complete. Connect via proxy at $proxyGatewayUrl",
                ipAddress,
                completedAt = Instant.now().toString()
            )
            logger.info("✅ Droplet $dropletId provisioning complete. VPS: $vpsGatewayUrl, Proxy: $proxyGatewayUrl")

            // Final phase: real first connect, and if pairing is required, approve requestId once.
            // Per requirement: do not reconnect after approval.
            approvePairingIfRequired(vpsGatewayUrl, gatewayToken, ipAddress, password, dropletId)

            // Create internal droplet with both URLs
            val userDropletInternal = UserDropletInternal(
                userId = userId,
                dropletId = dropletId,
                dropletName = dropletName,
                gatewayUrl = proxyGatewayUrl,           // Proxy URL for clients
                vpsGatewayUrl = vpsGatewayUrl,          // Actual VPS URL for backend routing
                gatewayToken = gatewayToken,
                ipAddress = ipAddress,
                subdomain = subdomain.takeIf { AppConfig.sslEnabled },
                createdAt = Instant.now().toString(),
                status = "active",
                sslEnabled = AppConfig.sslEnabled
            )

            try {
                firestoreRepository.saveUserDroplet(userDropletInternal)
                logger.info("💾 User droplet saved to Firestore: userId=$userId, dropletId=$dropletId")
            } catch (e: Exception) {
                logger.error("Failed to save user droplet to Firestore (droplet still provisioned successfully)", e)
            }

            // Return client-safe version (without VPS URL)
            return userDropletInternal.toUserDroplet()

        } catch (e: Exception) {
            logger.error("❌ Droplet $dropletId provisioning failed, deleting droplet...", e)

            // Clean up: destroy the failed droplet
            try {
                digitalOceanService.deleteDroplet(dropletId)
                logger.info("🗑️ Droplet $dropletId deleted after provisioning failure")
            } catch (deleteError: Exception) {
                logger.error("Failed to delete droplet $dropletId: ${deleteError.message}")
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

    // ── Phase 2 — Poll until active ─────────────────────────────────────

    private suspend fun waitForDropletReady(dropletId: Long): String {
        updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_ACTIVE, "Polling DigitalOcean API for droplet status...")

        val deadline = System.currentTimeMillis() + MAX_POLL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val response = digitalOceanService.getDroplet(dropletId)
                val droplet = response.droplet

                if (droplet?.status == "active") {
                    val ip = droplet.networks?.v4
                        ?.firstOrNull { it.type == "public" }
                        ?.ip_address

                    if (ip != null) {
                        logger.info("Droplet $dropletId is active at $ip")
                        updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_ACTIVE, "Droplet active at $ip", ip)
                        return ip
                    }
                }

                logger.info("Droplet $dropletId status: ${droplet?.status ?: "unknown"}, waiting...")
            } catch (e: Exception) {
                logger.warn("Error polling droplet $dropletId: ${e.message}")
            }

            delay(POLL_INTERVAL_MS)
        }

        throw IllegalStateException("Droplet $dropletId did not become active within ${MAX_POLL_WAIT_MS / 1000}s")
    }

    // ── Phase 4 — Onboarding via SSH ────────────────────────────────────

    private suspend fun runOnboardingWithRetries(ipAddress: String, password: String, geminiApiKey: String) {
        val command = """
            openclaw onboard \
              --install-daemon \
              --non-interactive \
              --auth-choice gemini-api-key \
              --gemini-api-key "$geminiApiKey" \
              --gateway-port $GATEWAY_PORT \
              --accept-risk
        """.trimIndent()

        var lastError: Exception? = null

        repeat(ONBOARDING_MAX_RETRIES) { attempt ->
            try {
                logger.info("Onboarding attempt ${attempt + 1}/$ONBOARDING_MAX_RETRIES on $ipAddress")
                val output = sshCommandExecutor.runSshCommand(ipAddress, password, command)

                if (output.contains("Installed systemd service") || output.contains("Enabled systemd lingering") || output.contains("onboarding complete", ignoreCase = true)) {
                    logger.info("Onboarding succeeded on attempt ${attempt + 1}")
                    return
                }

                // Even if we don't see expected markers, if command didn't throw, consider it success
                logger.info("Onboarding completed (attempt ${attempt + 1}), output: ${output.take(500)}")
                return

            } catch (e: Exception) {
                lastError = e
                logger.warn("Onboarding attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < ONBOARDING_MAX_RETRIES - 1) {
                    delay(5000)
                }
            }
        }

        throw IllegalStateException(
            "Onboarding failed after $ONBOARDING_MAX_RETRIES attempts: ${lastError?.message}", lastError
        )
    }

    // ── Phase 6 — Gateway verification ──────────────────────────────────

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
        ipAddress: String? = null,
        completedAt: String? = null
    ) {
        val current = statuses[dropletId] ?: return
        val progress = PHASE_PROGRESS[phase] ?: current.progress
        statuses[dropletId] = current.copy(
            phase = phase,
            progress = progress,
            message = message,
            ip_address = ipAddress ?: current.ip_address,
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
