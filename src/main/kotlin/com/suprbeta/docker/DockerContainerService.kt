package com.suprbeta.docker

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.docker.models.*
import io.ktor.server.application.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Service for managing Docker containers on host VPS instances.
 * 
 * This service creates, configures, and manages OpenClaw containers
 * via SSH commands to the host VPS.
 */
class DockerContainerService(
    private val sshCommandExecutor: SshCommandExecutor,
    private val portAllocator: ContainerPortAllocator,
    private val application: Application
) {
    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    
    companion object {
        private const val CONTAINER_IMAGE = "suprclaw/openclaw:latest"
        private const val CONTAINER_PORT = 18789
        private const val DEFAULT_MEMORY_LIMIT = "512m"
        private const val DEFAULT_CPU_LIMIT = "0.5"
    }
    
    /**
     * Creates a new OpenClaw container on the specified host.
     * 
     * @param hostIp The host VPS IP address
     * @param userId The user ID for this container
     * @param gatewayToken OpenClaw gateway authentication token
     * @param hookToken Webhook token for task notifications
     * @param supabaseConfig User's Supabase configuration
     * @param mcpTools List of MCP tools to configure
     * @param hostPort The allocated host port (from ContainerPortAllocator)
     * @return ContainerInfo with container details
     */
    suspend fun createContainer(
        hostIp: String,
        userId: String,
        subdomain: String,
        gatewayToken: String,
        hookToken: String,
        supabaseConfig: SupabaseConfig,
        mcpTools: List<McpToolConfig>,
        hostPort: Int
    ): ContainerInfo {
        logger.info("Creating container for user $userId on host $hostIp with port $hostPort")

        // Ensure image exists on host
        ensureImageExists(hostIp)

        // Generate container name (sanitize userId for Docker naming rules)
        val sanitizedUserId = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(20)
        val containerName = "openclaw-$sanitizedUserId-${System.currentTimeMillis()}"
        
        // Prepare MCP config JSON
        val mcpConfigJson = json.encodeToString(mapOf("tools" to mcpTools))
        
        // Build environment variables
        val envVars = buildEnvironmentVariables(
            gatewayToken = gatewayToken,
            hookToken = hookToken,
            supabaseConfig = supabaseConfig,
            mcpConfigJson = mcpConfigJson,
            userId = userId
        )
        
        // Build docker run command
        val dockerCommand = buildDockerRunCommand(
            containerName = containerName,
            hostPort = hostPort,
            envVars = envVars
        )
        
        // Execute docker run
        val output = sshCommandExecutor.runSshCommand(hostIp, dockerCommand)
        val containerId = output.trim()
        
        if (containerId.isBlank() || containerId.length < 12) {
            throw IllegalStateException("Failed to create container. Output: $output")
        }
        
        logger.info("Container created: $containerId")
        
        // Wait briefly for container to start
        kotlinx.coroutines.delay(2000)
        
        // Verify container is running
        val status = getContainerStatus(hostIp, containerId)
        if (!status.contains("running", ignoreCase = true)) {
            val logs = getContainerLogs(hostIp, containerId)
            throw IllegalStateException("Container not running. Status: $status. Logs: $logs")
        }
        
        return ContainerInfo(
            containerId = containerId,
            userId = userId,
            port = hostPort,
            subdomain = subdomain,
            gatewayToken = gatewayToken,
            supabaseProjectRef = supabaseConfig.projectRef,
            createdAt = Instant.now().toString(),
            status = ContainerInfo.STATUS_RUNNING
        )
    }
    
    /**
     * Starts a stopped container.
     */
    suspend fun startContainer(hostIp: String, containerId: String) {
        logger.info("Starting container $containerId on $hostIp")
        sshCommandExecutor.runSshCommand(hostIp, "docker start $containerId")
    }
    
    /**
     * Stops a running container.
     */
    suspend fun stopContainer(hostIp: String, containerId: String) {
        logger.info("Stopping container $containerId on $hostIp")
        sshCommandExecutor.runSshCommand(hostIp, "docker stop $containerId")
    }
    
    /**
     * Deletes a container permanently.
     */
    suspend fun deleteContainer(hostIp: String, containerId: String) {
        logger.info("Deleting container $containerId on $hostIp")
        // Stop first (force if needed), then remove
        sshCommandExecutor.runSshCommand(hostIp, "docker rm -f $containerId 2>/dev/null || true")
    }
    
    /**
     * Gets the status of a container.
     */
    suspend fun getContainerStatus(hostIp: String, containerId: String): String {
        return try {
            sshCommandExecutor.runSshCommand(
                hostIp, 
                "docker ps --filter \"id=$containerId\" --format '{{.Status}}' 2>/dev/null || echo 'not found'"
            ).trim()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
    
    /**
     * Gets the logs of a container.
     */
    suspend fun getContainerLogs(hostIp: String, containerId: String, tail: Int = 50): String {
        return try {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker logs --tail $tail $containerId 2>&1 || echo 'No logs available'"
            )
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
    
    /**
     * Executes a command inside a container.
     */
    suspend fun execInContainer(hostIp: String, containerId: String, command: String): String {
        return sshCommandExecutor.runSshCommand(
            hostIp,
            "docker exec $containerId $command"
        )
    }
    
    /**
     * Lists all containers on a host.
     */
    suspend fun listContainers(hostIp: String): List<String> {
        val output = sshCommandExecutor.runSshCommand(
            hostIp,
            "docker ps --format '{{.ID}}|{{.Names}}|{{.Status}}' 2>/dev/null || echo ''"
        )
        return output.lines().filter { it.isNotBlank() }
    }
    
    /**
     * Checks if an OpenClaw image exists on the host, builds it if not.
     */
    private suspend fun ensureImageExists(hostIp: String) {
        val imageCheck = sshCommandExecutor.runSshCommand(
            hostIp,
            "docker images --format '{{.Repository}}:{{.Tag}}' | grep suprclaw/openclaw || echo 'NOT_FOUND'"
        ).trim()
        
        if (imageCheck == "NOT_FOUND") {
            logger.warn("OpenClaw image not found on $hostIp. Need to build or pull it.")
            // In production, you might want to pull from a registry
            // For now, we'll expect it to be pre-built
            throw IllegalStateException(
                "OpenClaw image not found on host $hostIp. " +
                "Please build it first: docker build -t suprclaw/openclaw:latest ."
            )
        }
    }
    
    /**
     * Builds environment variable arguments for docker run.
     */
    private fun buildEnvironmentVariables(
        gatewayToken: String,
        hookToken: String,
        supabaseConfig: SupabaseConfig,
        mcpConfigJson: String,
        userId: String
    ): Map<String, String> {
        return mapOf(
            "GATEWAY_TOKEN" to gatewayToken,
            "HOOK_TOKEN" to hookToken,
            "SUPABASE_URL" to supabaseConfig.url,
            "SUPABASE_SERVICE_KEY" to supabaseConfig.serviceKey,
            "SUPABASE_PROJECT_REF" to supabaseConfig.projectRef,
            "MCP_CONFIG_JSON" to mcpConfigJson,
            "USER_ID" to userId
        )
    }
    
    /**
     * Builds the docker run command with all necessary options.
     */
    private fun buildDockerRunCommand(
        containerName: String,
        hostPort: Int,
        envVars: Map<String, String>
    ): String {
        val envArgs = envVars.entries.joinToString(" ") { (key, value) ->
            val escapedValue = value.replace("'", "'\\''")
            "-e $key='$escapedValue'"
        }
        
        return """
            docker run -d \
                --name $containerName \
                --restart unless-stopped \
                -p 127.0.0.1:$hostPort:$CONTAINER_PORT \
                --memory=$DEFAULT_MEMORY_LIMIT \
                --cpus=$DEFAULT_CPU_LIMIT \
                --log-driver json-file \
                --log-opt max-size=10m \
                --log-opt max-file=3 \
                $envArgs \
                $CONTAINER_IMAGE
        """.trimIndent().replace("\n", " ").trim()
    }
    
}
