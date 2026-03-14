package com.suprbeta.docker

import com.suprbeta.config.AppConfig
import com.suprbeta.core.SshCommandExecutor
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
import com.suprbeta.runtime.RuntimePaths
import io.ktor.server.application.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val sshCommandExecutor: SshCommandExecutor,
    private val application: Application
) : com.suprbeta.digitalocean.DropletProvisioningService {

    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    private val secureRandom = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private val dnsPropagationTimeoutMs = AppConfig.dockerDnsPropagationTimeoutSeconds * 1_000L
    
    /** In-memory status map keyed by container ID (used as droplet ID). */
    val statuses = ConcurrentHashMap<Long, ProvisioningStatus>()
    private val statusOwners = ConcurrentHashMap<Long, String>()
    private val provisioningIdsByUser = ConcurrentHashMap<String, Long>()
    
    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WAIT_MS = 300_000L
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

    override suspend fun createAndProvision(name: String, userId: String): com.suprbeta.digitalocean.DropletProvisioningService.CreateResult {
        val existingDroplet = firestoreRepository.getUserDropletInternal(userId)
        if (existingDroplet != null && existingDroplet.userId.isNotBlank()) {
            throw IllegalStateException("User already has a droplet")
        }

        val existingProvisioningId = provisioningIdsByUser[userId]
        if (existingProvisioningId != null) {
            val existingStatus = statuses[existingProvisioningId]
            if (existingStatus != null &&
                existingStatus.phase != ProvisioningStatus.PHASE_COMPLETE &&
                existingStatus.phase != ProvisioningStatus.PHASE_FAILED
            ) {
                throw IllegalStateException("Provisioning already in progress for user")
            }
        }

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
        statusOwners[containerDropletId] = userId
        provisioningIdsByUser[userId] = containerDropletId
        
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
            subdomain = generateSubdomain(userId)

            // Phase 5: Create container
            updateStatus(dropletId, "creating_container", "Creating PicoClaw container...")
            val supabaseConfig = SupabaseConfig(
                url = safeEndpoint,
                serviceKey = safeServiceKey,
                projectRef = safeProjectRef,
                schema = safeSchema,
                apiKey = safeServiceKey  // self-hosted: service key is also used as Kong api key
            )

            val mcpTools = emptyList<McpToolConfig>()
            val mcpRoutesJson = "{}"
            val mcpMcporterJson = """{"mcpServers":{}}"""

            val awsAccessKeyId = dotenv["AWS_ACCESS_KEY_ID"] ?: System.getenv("AWS_ACCESS_KEY_ID") ?: ""
            val awsSecretAccessKey = dotenv["AWS_SECRET_ACCESS_KEY"] ?: System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""
            val awsRegion = dotenv["AWS_REGION"] ?: System.getenv("AWS_REGION") ?: "us-east-1"
            val awsBearerTokenBedrock = dotenv["AWS_BEARER_TOKEN_BEDROCK"] ?: System.getenv("AWS_BEARER_TOKEN_BEDROCK") ?: ""
            val liteLlmApiBase = dotenv["LITELLM_API_BASE"] ?: System.getenv("LITELLM_API_BASE") ?: ""
            val liteLlmApiKey = dotenv["LITELLM_API_KEY"] ?: System.getenv("LITELLM_API_KEY") ?: ""
            val liteLlmModelId = dotenv["LITELLM_MODEL_ID"] ?: System.getenv("LITELLM_MODEL_ID") ?: ""

            val containerInfo = containerService.createContainer(
                hostIp = hostIp,
                userId = userId,
                subdomain = subdomain,
                gatewayToken = gatewayToken,
                supabaseConfig = supabaseConfig,
                mcpTools = mcpTools,
                hostPort = allocatedPort,
                mcpRoutesJson = mcpRoutesJson,
                mcpMcporterJson = mcpMcporterJson,
                webhookBaseUrl = managementService.webhookBaseUrl,
                awsAccessKeyId = awsAccessKeyId,
                awsSecretAccessKey = awsSecretAccessKey,
                awsRegion = awsRegion,
                awsBearerTokenBedrock = awsBearerTokenBedrock,
                liteLlmApiBase = liteLlmApiBase,
                liteLlmApiKey = liteLlmApiKey,
                liteLlmModelId = liteLlmModelId
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
            runCatching {
                waitForDnsResolution(createdSubdomain, hostIp)
            }.onFailure { error ->
                logger.warn(
                    "DNS propagation check did not complete for $createdSubdomain -> $hostIp: ${error.message}. Continuing with provisioning."
                )
            }

            // Phase 8: Verify gateway
            updateStatus(dropletId, ProvisioningStatus.PHASE_VERIFYING, "Verifying gateway status...")
            verifyGateway(hostIp, allocatedPort, containerInfo.containerId)

            // Phase 9: Initialize user project tables
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Initializing user database...")
            val vpsGatewayUrl = "https://$subdomain"

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
                supabaseProjectRef = safeProjectRef,
                supabaseServiceKey = safeServiceKey,
                supabaseUrl = safeEndpoint,
                supabaseSchema = safeSchema,
                createdAt = Instant.now().toString(),
                status = "active",
                configuredMcpTools = emptyList(),
                agentRuntime = "picoclaw"
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
                val identityPath = "${RuntimePaths.leadWorkspace}/IDENTITY.md"
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

    override fun getStatusForUser(dropletId: Long, userId: String): ProvisioningStatus? =
        statuses[dropletId]?.takeIf { statusOwners[dropletId] == userId }

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

        provisioningIdsByUser.remove(userId)?.let { provisioningId ->
            statuses.remove(provisioningId)
            statusOwners.remove(provisioningId)
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
                    "curl -fsS -o /dev/null --connect-timeout 5 http://127.0.0.1:$port/health && echo 'READY' || echo 'NOT_READY'"
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

        val supervisorLogs = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker exec $containerId sh -lc 'for f in /var/log/supervisor/picoclaw-gateway.log /var/log/supervisor/picoclaw-gateway.err; do if [ -f \"${'$'}f\" ]; then echo \"===== ${'$'}f =====\"; tail -n 120 \"${'$'}f\"; fi; done' 2>&1 || true"
            ).trim()
        }.getOrDefault("supervisor child logs unavailable")

        val runtimeLogs = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker exec $containerId sh -lc 'if [ -d /tmp/picoclaw ]; then ls -lah /tmp/picoclaw; for f in /tmp/picoclaw/*.log; do [ -e \"${'$'}f\" ] || continue; echo \"===== ${'$'}f =====\"; tail -n 120 \"${'$'}f\"; done; else echo \"/tmp/picoclaw missing\"; fi' 2>&1 || true"
            ).trim()
        }.getOrDefault("picoclaw runtime logs unavailable")

        val logs = runCatching {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker logs --tail 120 $containerId 2>&1 || true"
            ).trim()
        }.getOrDefault("container logs unavailable")

        logger.error("Gateway readiness timed out for container $containerId on port $port. Inspect: $inspect")
        logger.error("Container supervisor status for $containerId:\n$supervisor")
        logger.error("Container listening sockets for $containerId:\n$sockets")
        logger.error("Container supervisor child logs for $containerId:\n$supervisorLogs")
        logger.error("Container PicoClaw runtime logs for $containerId:\n$runtimeLogs")
        logger.error("Container logs for $containerId:\n$logs")
    }

    private suspend fun waitForDnsResolution(hostname: String, expectedIp: String) {
        val deadline = System.currentTimeMillis() + dnsPropagationTimeoutMs
        var attempts = 0

        while (System.currentTimeMillis() < deadline) {
            attempts += 1
            val publicAddresses = resolveWithPublicDns(hostname)
            if (publicAddresses.contains(expectedIp)) {
                logger.info("DNS propagated for $hostname via public resolver -> ${publicAddresses.joinToString(", ")}")
                return
            }

            val systemAddresses = resolveWithSystemDns(hostname)
            if (systemAddresses.contains(expectedIp)) {
                logger.info("DNS propagated for $hostname via system resolver -> ${systemAddresses.joinToString(", ")}")
                return
            }

            val combined = (systemAddresses + publicAddresses).distinct()
            if (attempts >= 3) {
                if (combined.isEmpty()) {
                    logger.info("DNS for $hostname is still propagating after provider confirmation; continuing without blocking")
                } else {
                    logger.info(
                        "DNS for $hostname currently resolves to ${combined.joinToString(", ")} while waiting for $expectedIp; continuing without blocking"
                    )
                }
                return
            }

            if (combined.isEmpty()) {
                logger.info("DNS for $hostname is not resolvable yet; waiting...")
            } else {
                logger.info("DNS for $hostname currently resolves to ${combined.joinToString(", ")}; waiting for $expectedIp")
            }
            delay(2_000L)
        }

        logger.info("DNS for $hostname did not visibly propagate within ${dnsPropagationTimeoutMs / 1000}s; continuing with provider-confirmed record")
    }

    private suspend fun resolveWithSystemDns(hostname: String): List<String> {
        return try {
            withContext(Dispatchers.IO) {
                InetAddress.getAllByName(hostname)
                    .map { it.hostAddress }
                    .distinct()
            }
        } catch (_: UnknownHostException) {
            emptyList()
        }
    }

    private suspend fun resolveWithPublicDns(hostname: String): List<String> {
        val encodedName = URLEncoder.encode(hostname, Charsets.UTF_8)
        val cacheBust = System.currentTimeMillis()
        val endpoints = listOf(
            "https://dns.google/resolve?name=$encodedName&type=A&nonce=$cacheBust",
            "https://cloudflare-dns.com/dns-query?name=$encodedName&type=A&nonce=$cacheBust"
        )

        for (endpoint in endpoints) {
            val addresses = runCatching {
                withContext(Dispatchers.IO) {
                    val connection = URI.create(endpoint).toURL().openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/dns-json")
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("Pragma", "no-cache")
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = json.parseToJsonElement(response).jsonObject
                    root["Answer"]
                        ?.jsonArray
                        ?.mapNotNull { answer ->
                            val record = answer.jsonObject
                            if (record["type"]?.jsonPrimitive?.content == "1") {
                                record["data"]?.jsonPrimitive?.content
                            } else {
                                null
                            }
                        }
                        ?.distinct()
                        .orEmpty()
                }
            }.getOrElse { error ->
                logger.debug("Public DNS lookup failed for $hostname via $endpoint: ${error.message}")
                emptyList()
            }

            if (addresses.isNotEmpty()) {
                return addresses
            }
        }

        return emptyList()
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

    private fun generateGatewayToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generatePassword(): String {
        val chars = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toCharArray()
        return buildString {
            repeat(20) {
                append(chars[secureRandom.nextInt(chars.size)])
            }
        }
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
            configuredMcpTools = droplet.configuredMcpTools,
            deploymentMode = "docker",
            agentRuntime = droplet.agentRuntime
        )

        firestoreRepository.saveUserDroplet(internalDroplet)
    }
}
