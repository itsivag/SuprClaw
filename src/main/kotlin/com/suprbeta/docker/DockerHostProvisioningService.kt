package com.suprbeta.docker

import com.suprbeta.config.AppConfig
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.DropletMcpService
import com.suprbeta.digitalocean.McpToolRegistry
import io.github.cdimascio.dotenv.dotenv
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.docker.models.*
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.provider.DnsProvider
import com.suprbeta.provider.VpsService
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SupabaseManagementService
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Provisioning service for Docker-based multi-tenant architecture.
 * 
 * This service orchestrates the creation of user environments on shared host VPS instances,
 * using containers instead of dedicated VMs for better resource utilization.
 */
class DockerHostProvisioningService(
    private val vpsService: VpsService,
    private val hostPoolManager: HostPoolManager,
    private val containerService: DockerContainerService,
    private val traefikManager: TraefikManager,
    private val portAllocator: ContainerPortAllocator,
    private val dnsProvider: DnsProvider,
    private val firestoreRepository: FirestoreRepository,
    private val schemaRepository: SupabaseSchemaRepository,
    private val managementService: SupabaseManagementService,
    private val agentRepository: SupabaseAgentRepository,
    private val userClientProvider: UserSupabaseClientProvider,
    private val openClawConnector: OpenClawConnector,
    private val sshCommandExecutor: SshCommandExecutor,
    private val dropletMcpService: DropletMcpService,
    private val application: Application
) : com.suprbeta.digitalocean.DropletProvisioningService {

    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true }
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    
    /** In-memory status map keyed by container ID (used as droplet ID). */
    val statuses = ConcurrentHashMap<Long, ProvisioningStatus>()
    
    companion object {
        private const val GATEWAY_PORT = 18789
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WAIT_MS = 300_000L
        private const val DNS_PROPAGATION_TIMEOUT_MS = 120_000L
        
        // Progress values for each phase (0.0 to 1.0)
        private val PHASE_PROGRESS = mapOf(
            ProvisioningStatus.PHASE_CREATING to 0.0,
            "waiting_host" to 0.125,
            "allocating_port" to 0.25,
            "creating_container" to 0.35,
            ProvisioningStatus.PHASE_CONFIGURING to 0.55,
            ProvisioningStatus.PHASE_DNS to 0.65,
            ProvisioningStatus.PHASE_VERIFYING to 0.75,
            ProvisioningStatus.PHASE_NGINX to 0.875,
            ProvisioningStatus.PHASE_COMPLETE to 1.0,
            ProvisioningStatus.PHASE_FAILED to 0.0
        )
    }

    override suspend fun createAndProvision(name: String): com.suprbeta.digitalocean.DropletProvisioningService.CreateResult {
        val password = generatePassword()

        // Use a collision-free unique ID for this provisioning session
        val containerDropletId = SecureRandom().nextLong().and(0x7FFF_FFFF_FFFF_FFFFL)
        
        val status = ProvisioningStatus(
            droplet_id = containerDropletId,
            droplet_name = name,
            phase = ProvisioningStatus.PHASE_CREATING,
            progress = 0.0,
            message = "Starting container provisioning...",
            started_at = Instant.now().toString()
        )
        statuses[containerDropletId] = status
        
        return com.suprbeta.digitalocean.DropletProvisioningService.CreateResult(
            dropletId = containerDropletId,
            status = status,
            password = password
        )
    }

    override suspend fun provisionDroplet(
        dropletId: Long,
        password: String,
        userId: String
    ): UserDroplet {
        var projectRef: String? = null
        var hostId: Long? = null
        var hostIp: String? = null
        var allocatedPort: Int? = null
        var containerId: String? = null
        var subdomain: String? = null

        try {
            val statusNow = statuses[dropletId]
            val dropletName = statusNow?.droplet_name ?: "container-$dropletId"

            // Phase 1: Get or create host with capacity
            updateStatus(dropletId, "waiting_host", "Finding host with available capacity...")
            val (selectedHostId, selectedHostIp) = hostPoolManager.getOrCreateHostForNewUser()
            hostId = selectedHostId
            hostIp = selectedHostIp
            logger.info("Using host $hostId at $hostIp for user $userId")

            // Phase 2: Create Supabase project
            updateStatus(dropletId, "waiting_host", "Creating Supabase project...")
            var resolvedServiceKey: String? = null
            var resolvedEndpoint: String? = null

            coroutineScope {
                val createProjectDeferred = async {
                    val sanitizedName = dropletName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                    val result = managementService.createProject("suprclaw-$sanitizedName")
                    managementService.waitForProjectActive(result.projectRef)
                    val sk = managementService.getServiceKey(result.projectRef)
                    Triple(result.projectRef, sk, result.endpoint)
                }
                val (pRef, sk, endpoint) = createProjectDeferred.await()
                projectRef = pRef
                resolvedServiceKey = sk
                resolvedEndpoint = endpoint
                logger.info("Supabase project $projectRef ready")
            }

            val safeProjectRef = projectRef ?: throw IllegalStateException("ProjectRef not available")
            val safeServiceKey = resolvedServiceKey ?: throw IllegalStateException("ServiceKey not available")
            val safeEndpoint = resolvedEndpoint ?: throw IllegalStateException("Endpoint not available")
            val safeSchema = managementService.resolveSchema(safeProjectRef)

            // Phase 3: Allocate port
            updateStatus(dropletId, "allocating_port", "Allocating port on host...")
            allocatedPort = portAllocator.allocatePort(hostId)
            logger.info("Port allocated: $allocatedPort on host $hostId")

            // Phase 4: Generate tokens and subdomain
            updateStatus(dropletId, "creating_container", "Generating gateway tokens...")
            val gatewayToken = generateGatewayToken()
            val hookToken = generateGatewayToken()
            subdomain = generateSubdomain(userId)

            // Phase 5: Create container
            updateStatus(dropletId, "creating_container", "Creating OpenClaw container...")
            val supabaseConfig = SupabaseConfig(
                url = safeEndpoint,
                serviceKey = safeServiceKey,
                projectRef = safeProjectRef,
                schema = safeSchema,
                apiKey = safeServiceKey  // self-hosted: service key is also used as Kong api key
            )

            val mcpTools = McpToolRegistry.defaultTools.map { toolName ->
                val toolDef = McpToolRegistry.get(toolName)
                val envVars: Map<String, String> = if (toolDef != null) {
                    val envValue = dotenv[toolDef.authEnvVar] ?: System.getenv(toolDef.authEnvVar) ?: ""
                    mapOf(toolDef.authEnvVar to envValue)
                } else {
                    emptyMap()
                }
                McpToolConfig(name = toolName, envVars = envVars)
            }

            val supabaseMcpUrl = dotenv["SUPABASE_MCP_URL"] ?: System.getenv("SUPABASE_MCP_URL") ?: ""
            val mcpRoutesJson = run {
                val entries = mcpTools.mapNotNull { toolConfig ->
                    val def = McpToolRegistry.get(toolConfig.name) ?: return@mapNotNull null
                    val auth = when (def.authType) {
                        "bearer"      -> """{"type":"bearer","envVar":"${def.authEnvVar}"}"""
                        "path-prefix" -> """{"type":"path-prefix","envVar":"${def.authEnvVar}","template":"${def.authTemplate}"}"""
                        else           -> """{"type":"${def.authType}","envVar":"${def.authEnvVar}"}"""
                    }
                    val upstream = if (def.name == "supabase") supabaseMcpUrl.ifBlank { def.upstream } else def.upstream
                    """"${def.name}":{"upstream":"$upstream","auth":$auth}"""
                }
                "{${entries.joinToString(",")}}"
            }

            val mcpMcporterJson = run {
                val servers = mcpTools.mapNotNull { toolConfig ->
                    val def = McpToolRegistry.get(toolConfig.name) ?: return@mapNotNull null
                    val url = def.mcporterUrlTemplate.replace("{projectRef}", safeProjectRef)
                    """"${def.name}":{"url":"$url","lifecycle":"keep-alive"}"""
                }
                """{"mcpServers":{${servers.joinToString(",")}}}"""
            }

            val awsAccessKeyId = dotenv["AWS_ACCESS_KEY_ID"] ?: System.getenv("AWS_ACCESS_KEY_ID") ?: ""
            val awsSecretAccessKey = dotenv["AWS_SECRET_ACCESS_KEY"] ?: System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""
            val awsRegion = dotenv["AWS_REGION"] ?: System.getenv("AWS_REGION") ?: "us-east-1"
            val awsBearerTokenBedrock = dotenv["AWS_BEARER_TOKEN_BEDROCK"] ?: System.getenv("AWS_BEARER_TOKEN_BEDROCK") ?: ""

            val containerInfo = containerService.createContainer(
                hostIp = hostIp,
                userId = userId,
                subdomain = subdomain,
                gatewayToken = gatewayToken,
                hookToken = hookToken,
                supabaseConfig = supabaseConfig,
                mcpTools = mcpTools,
                hostPort = allocatedPort,
                mcpRoutesJson = mcpRoutesJson,
                mcpMcporterJson = mcpMcporterJson,
                webhookBaseUrl = managementService.webhookBaseUrl,
                awsAccessKeyId = awsAccessKeyId,
                awsSecretAccessKey = awsSecretAccessKey,
                awsRegion = awsRegion,
                awsBearerTokenBedrock = awsBearerTokenBedrock
            )

            containerId = containerInfo.containerId
            logger.info("Container created: $containerId")

            // Assign user to host (atomic)
            hostPoolManager.assignUserToHost(userId, hostId)

            // Phase 6: Configure Traefik
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Configuring Traefik route...")
            traefikManager.addRoute(hostIp, subdomain, allocatedPort)
            logger.info("Traefik route added: $subdomain -> port $allocatedPort")

            // Phase 7: Configure DNS
            updateStatus(dropletId, ProvisioningStatus.PHASE_DNS, "Creating DNS record...")
            val dnsLabel = subdomain.removeSuffix(".suprclaw.com")
            val createdSubdomain = dnsProvider.createDnsRecord(dnsLabel, hostIp)
            logger.info("DNS record created: $createdSubdomain -> $hostIp")
            waitForDnsResolution(createdSubdomain, hostIp)

            // Phase 8: Verify gateway
            updateStatus(dropletId, ProvisioningStatus.PHASE_VERIFYING, "Verifying gateway status...")
            verifyGateway(hostIp, allocatedPort, containerInfo.containerId)

            // Phase 9: Initialize user project tables
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Initializing user database...")
            val vpsGatewayUrl = "https://$subdomain"

            // Phase 8b: Approve pairing if required
            approvePairingIfRequired(vpsGatewayUrl, gatewayToken, hostIp, containerId, dropletId)
            val proxyGatewayUrl = "wss://api.suprclaw.com"

            schemaRepository.initializeUserProject(
                managementService = managementService,
                projectRef = safeProjectRef,
                userId = userId,
                vpsGatewayUrl = vpsGatewayUrl,
                gatewayToken = gatewayToken
            )

            // Phase 10: Save to Firestore + save default agent
            val userHostDroplet = UserHostDroplet(
                userId = userId,
                hostServerId = hostId,
                hostIp = hostIp,
                containerId = containerId,
                port = allocatedPort,
                subdomain = subdomain,
                gatewayUrl = proxyGatewayUrl,
                vpsGatewayUrl = vpsGatewayUrl,
                gatewayToken = gatewayToken,
                hookToken = hookToken,
                supabaseProjectRef = safeProjectRef,
                supabaseServiceKey = safeServiceKey,
                supabaseUrl = safeEndpoint,
                supabaseSchema = safeSchema,
                createdAt = Instant.now().toString(),
                status = "active",
                configuredMcpTools = McpToolRegistry.defaultTools
            )

            saveUserHostDroplet(userHostDroplet)

            val userClient = userClientProvider.getClient(safeEndpoint, safeServiceKey, safeSchema)
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

            // Patch IDENTITY.md in the container with the Supabase-generated agent ID
            val leadAgent = agentRepository.getLeadAgent(userClient)
            if (leadAgent?.id != null) {
                val identityPath = "/home/openclaw/.openclaw/workspace/IDENTITY.md"
                sshCommandExecutor.runSshCommand(
                    hostIp,
                    "docker exec $containerId sed -i 's/UID in Supabase: `unknown`/UID in Supabase: `${leadAgent.id}`/' $identityPath"
                )
                logger.info("IDENTITY.md patched with lead agent ID: ${leadAgent.id}")
            }

            updateStatus(
                dropletId,
                ProvisioningStatus.PHASE_COMPLETE,
                "Provisioning complete. Connect via proxy at $proxyGatewayUrl",
                completedAt = Instant.now().toString()
            )

            logger.info("✅ Provisioning complete for user $userId")
            return userHostDroplet.toUserDroplet()
            
        } catch (e: Exception) {
            logger.error("❌ Provisioning failed for droplet $dropletId", e)
            
            // Cleanup
            cleanupOnFailure(
                hostId = hostId,
                hostIp = hostIp,
                containerId = containerId,
                port = allocatedPort,
                subdomain = subdomain,
                projectRef = projectRef,
                userId = userId
            )
            
            updateStatus(
                dropletId,
                ProvisioningStatus.PHASE_FAILED,
                "Provisioning failed: ${e.message}",
                error = e.stackTraceToString(),
                completedAt = Instant.now().toString()
            )
            
            throw e
        }
    }

    override fun getStatus(dropletId: Long): ProvisioningStatus? = statuses[dropletId]

    override suspend fun teardown(userId: String) {
        logger.info("Tearing down container environment for user $userId")

        val hostDroplet = firestoreRepository.getUserDropletInternal(userId)
            ?: throw IllegalStateException("No container found for user $userId")

        val hostInfo = hostPoolManager.getHostForUser(userId)
            ?: throw IllegalStateException("No host found for user $userId")

        // Retrieve the fields stored by saveUserHostDroplet:
        //   dropletName = full container ID
        //   sshKey      = container port (as string)
        //   ipAddress   = host IP
        val containerId = hostDroplet.dropletName
        val containerPort = hostDroplet.sshKey.toIntOrNull()
        val resolvedHostIp = hostDroplet.ipAddress

        val errors = mutableListOf<String>()

        // 1. Delete container
        try {
            if (containerId.isNotBlank()) {
                containerService.deleteContainer(resolvedHostIp, containerId)
                logger.info("🗑️ Container $containerId deleted")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete container", e)
            errors += "Container: ${e.message}"
        }

        // 1b. Remove devices volume (device pairing approvals are user-specific and no longer needed)
        try {
            val sanitizedUserId = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(20)
            sshCommandExecutor.runSshCommand(
                resolvedHostIp,
                "docker volume rm openclaw-devices-$sanitizedUserId 2>/dev/null || true"
            )
            logger.info("🗑️ Devices volume removed for user $userId")
        } catch (e: Exception) {
            logger.warn("Failed to remove devices volume (non-fatal): ${e.message}")
        }

        // 2. Remove Traefik route
        try {
            val sub = hostDroplet.subdomain ?: ""
            if (sub.isNotBlank()) {
                traefikManager.removeRoute(resolvedHostIp, sub)
                logger.info("🗑️ Traefik route removed")
            }
        } catch (e: Exception) {
            logger.error("Failed to remove Traefik route", e)
            errors += "Traefik route: ${e.message}"
        }

        // 3. Delete DNS record
        try {
            val sub = hostDroplet.subdomain
            if (!sub.isNullOrBlank()) {
                dnsProvider.deleteDnsRecord(sub.removeSuffix(".suprclaw.com"))
                logger.info("🗑️ DNS record deleted")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete DNS record", e)
            errors += "DNS: ${e.message}"
        }

        // 4. Release port
        if (containerPort != null) {
            try {
                portAllocator.releasePort(hostInfo.hostId, containerPort)
                logger.info("🗑️ Port $containerPort released")
            } catch (e: Exception) {
                logger.error("Failed to release port", e)
            }
        }

        // 5. Release user from host (atomic decrement)
        try {
            hostPoolManager.releaseUserFromHost(userId, hostInfo.hostId)
            logger.info("🗑️ User released from host")
        } catch (e: Exception) {
            logger.error("Failed to release user from host", e)
        }

        // 6. Delete Supabase project
        try {
            if (hostDroplet.supabaseProjectRef.isNotBlank()) {
                schemaRepository.cleanupUserProject(managementService, hostDroplet.supabaseProjectRef, userId)
                logger.info("🗑️ Supabase project deleted")
            }
        } catch (e: Exception) {
            logger.error("Failed to cleanup Supabase project", e)
            errors += "Supabase: ${e.message}"
        }

        // 7. Delete Firestore records
        try {
            firestoreRepository.deleteUserDroplet(userId)
            logger.info("🗑️ Firestore records deleted")
        } catch (e: Exception) {
            logger.error("Failed to delete Firestore records", e)
            errors += "Firestore: ${e.message}"
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException("Teardown partially failed: ${errors.joinToString("; ")}")
        }

        logger.info("✅ Teardown complete for user $userId")
    }

    // Helper methods
    
    private fun updateStatus(
        dropletId: Long,
        phase: String,
        message: String,
        completedAt: String? = null,
        error: String? = null
    ) {
        val current = statuses[dropletId] ?: return
        val progress = PHASE_PROGRESS[phase] ?: current.progress
        statuses[dropletId] = current.copy(
            phase = phase,
            progress = progress,
            message = message,
            completed_at = completedAt,
            error = error
        )
    }

    private suspend fun verifyGateway(hostIp: String, port: Int, containerId: String) {
        val maxWaitSeconds = AppConfig.dockerGatewayReadyTimeoutSeconds
        val deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L)
        var attempt = 0

        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            try {
                val health = runCatching {
                    sshCommandExecutor.runSshCommand(
                        hostIp,
                        "docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $containerId 2>/dev/null || echo unknown"
                    ).trim()
                }.getOrDefault("unknown")

                if (health == "healthy") {
                    logger.info("Gateway verified on port $port via container health")
                    return
                }

                // Require a successful HTTP response from the container ingress, not just any body.
                val output = sshCommandExecutor.runSshCommand(
                    hostIp,
                    "curl -fsS -o /dev/null --connect-timeout 5 http://127.0.0.1:$port/ && echo 'READY' || echo 'NOT_READY'"
                )

                if (output.trim() == "READY") {
                    logger.info("Gateway verified on port $port")
                    return
                }

                if (attempt % 5 == 0) {
                    logger.info("Gateway on port $port not ready yet (container health: $health)")
                }
            } catch (e: Exception) {
                logger.debug("Gateway not ready yet: ${e.message}")
            }
            delay(2000)
        }

        logGatewayDiagnostics(hostIp, containerId, port)
        throw IllegalStateException("Gateway did not become ready within ${maxWaitSeconds}s")
    }

    private suspend fun logGatewayDiagnostics(hostIp: String, containerId: String, port: Int) {
        val inspect = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker inspect --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} started={{.State.StartedAt}}' $containerId 2>&1 || true"
            ).trim()
        }.getOrDefault("inspect unavailable")

        val supervisor = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker exec $containerId supervisorctl status 2>&1 || true"
            ).trim()
        }.getOrDefault("supervisor status unavailable")

        val sockets = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker exec $containerId sh -lc 'ss -ltnp || netstat -ltnp' 2>&1 || true"
            ).trim()
        }.getOrDefault("socket diagnostics unavailable")

        val logs = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker logs --tail 120 $containerId 2>&1 || true"
            ).trim()
        }.getOrDefault("container logs unavailable")

        logger.error("Gateway readiness timed out for container $containerId on port $port. Inspect: $inspect")
        logger.error("Container supervisor status for $containerId:\n$supervisor")
        logger.error("Container listening sockets for $containerId:\n$sockets")
        logger.error("Container logs for $containerId:\n$logs")
    }

    private suspend fun waitForDnsResolution(hostname: String, expectedIp: String) {
        val deadline = System.currentTimeMillis() + DNS_PROPAGATION_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                val addresses = withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(hostname)
                        .map { it.hostAddress }
                        .distinct()
                }

                if (addresses.contains(expectedIp)) {
                    logger.info("DNS propagated for $hostname -> ${addresses.joinToString(", ")}")
                    return
                }

                logger.info("DNS for $hostname currently resolves to ${addresses.joinToString(", ")}; waiting for $expectedIp")
            } catch (_: UnknownHostException) {
                logger.info("DNS for $hostname is not resolvable yet; waiting...")
            }

            delay(2_000L)
        }

        throw IllegalStateException("DNS record $hostname did not resolve to $expectedIp within ${DNS_PROPAGATION_TIMEOUT_MS / 1000}s")
    }

    private suspend fun cleanupOnFailure(
        hostId: Long?,
        hostIp: String?,
        containerId: String?,
        port: Int?,
        subdomain: String?,
        projectRef: String?,
        userId: String
    ) {
        logger.info("Cleaning up after provisioning failure...")
        
        // Delete container if created
        if (containerId != null && hostIp != null) {
            try {
                containerService.deleteContainer(hostIp, containerId)
                logger.info("Cleaned up container $containerId")
            } catch (e: Exception) {
                logger.error("Failed to cleanup container", e)
            }
        }

        // Remove devices volume so repeated provisioning retries start with a clean device state.
        if (hostIp != null) {
            try {
                val sanitizedUserId = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(20)
                sshCommandExecutor.runSshCommand(
                    hostIp,
                    "docker volume rm openclaw-devices-$sanitizedUserId 2>/dev/null || true"
                )
                logger.info("Removed devices volume for user $userId")
            } catch (e: Exception) {
                logger.warn("Failed to remove devices volume during cleanup (non-fatal): ${e.message}")
            }
        }
        
        // Release port
        if (port != null && hostId != null) {
            try {
                portAllocator.releasePort(hostId, port)
                logger.info("Released port $port")
            } catch (e: Exception) {
                logger.error("Failed to release port", e)
            }
        }
        
        // Remove Traefik route
        if (subdomain != null && hostIp != null) {
            try {
                traefikManager.removeRoute(hostIp, subdomain)
                logger.info("Removed Traefik route")
            } catch (e: Exception) {
                logger.error("Failed to remove Traefik route", e)
            }
        }
        
        // Release user from host
        if (hostId != null) {
            try {
                hostPoolManager.releaseUserFromHost(userId, hostId)
            } catch (e: Exception) {
                logger.error("Failed to release user from host", e)
            }
        }
        
        // Delete Supabase project
        if (projectRef != null) {
            try {
                managementService.deleteProject(projectRef)
                logger.info("Deleted Supabase project $projectRef")
            } catch (e: Exception) {
                logger.error("Failed to delete Supabase project", e)
            }
        }
    }

    private suspend fun approvePairingIfRequired(
        vpsGatewayUrl: String,
        gatewayToken: String,
        hostIp: String,
        containerId: String,
        dropletId: Long
    ) {
        val session = openClawConnector.connect(token = gatewayToken, vpsGatewayUrl = vpsGatewayUrl)
        if (session == null) {
            logger.warn("Pairing phase skipped for droplet $dropletId: unable to open websocket to $vpsGatewayUrl")
            return
        }
        try {
            val deadlineMs = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadlineMs) {
                val inbound = withTimeoutOrNull(2_500L) { session.incoming.receive() } ?: continue
                if (inbound !is Frame.Text) continue
                val frame = json.decodeFromString<WebSocketFrame>(inbound.readText())

                if (frame.event == "connect.challenge") {
                    openClawConnector.handleConnectChallenge(session, gatewayToken, frame.payload, "android")
                    continue
                }

                if (frame.type == "res" && frame.error == null) {
                    logger.info("Pairing phase: connect succeeded for droplet $dropletId (no approval needed)")
                    return
                }

                val errorObj = frame.error?.jsonObject ?: continue
                val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: continue

                if (message == "pairing required") {
                    val requestId = runCatching {
                        errorObj["details"]?.jsonObject?.get("requestId")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()

                    if (requestId.isNullOrBlank()) {
                        logger.warn("Pairing phase: pairing required but no requestId for droplet $dropletId")
                        return
                    }

                    sshCommandExecutor.runSshCommand(
                        hostIp,
                        "docker exec $containerId su - openclaw -s /bin/sh -c 'openclaw devices approve $requestId || test -s /home/openclaw/.openclaw/devices/paired.json'"
                    )
                    logger.info("Pairing phase: approved requestId=$requestId (or pairing already present) for droplet $dropletId")
                    return
                }
            }
            logger.warn("Pairing phase timed out for droplet $dropletId")
        } catch (e: Exception) {
            logger.warn("Pairing phase failed for droplet $dropletId: ${e.message}")
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun generateGatewayToken(): String {
        val chars = "abcdef0123456789"
        return (1..48).map { chars.random() }.joinToString("")
    }

    private fun generatePassword(): String {
        val chars = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toCharArray()
        return (1..20).map { chars.random() }.joinToString("")
    }

    private fun generateSubdomain(userId: String): String {
        val sanitized = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        return "$sanitized.suprclaw.com"
    }

    private suspend fun saveUserHostDroplet(droplet: UserHostDroplet) {
        // Map into UserDropletInternal, repurposing unused-in-container-mode fields to
        // store data needed for teardown:
        //   dropletId   = host VPS server ID (for host pool lookup via user_host_mappings)
        //   dropletName = full container ID   (needed for `docker rm -f`)
        //   ipAddress   = host IP address     (needed to SSH to the host)
        //   sshKey      = container port      (needed to release from portAllocator)
        val internalDroplet = com.suprbeta.digitalocean.models.UserDropletInternal(
            userId = droplet.userId,
            dropletId = droplet.hostServerId,
            dropletName = droplet.containerId,
            gatewayUrl = droplet.gatewayUrl,
            vpsGatewayUrl = droplet.vpsGatewayUrl,
            gatewayToken = droplet.gatewayToken,
            hookToken = droplet.hookToken ?: "",
            ipAddress = droplet.hostIp,
            sshKey = droplet.port.toString(),
            subdomain = droplet.subdomain,
            createdAt = droplet.createdAt,
            status = droplet.status,
            sslEnabled = true,
            supabaseProjectRef = droplet.supabaseProjectRef,
            supabaseServiceKey = droplet.supabaseServiceKey,
            supabaseUrl = droplet.supabaseUrl,
            supabaseSchema = droplet.supabaseSchema,
            configuredMcpTools = droplet.configuredMcpTools
        )

        firestoreRepository.saveUserDroplet(internalDroplet)
    }
}
