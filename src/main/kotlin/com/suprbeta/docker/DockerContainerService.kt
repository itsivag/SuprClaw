package com.suprbeta.docker

import com.suprbeta.config.AppConfig
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
        private const val CONTAINER_PORT = 18789
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
        hostPort: Int,
        mcpRoutesJson: String = "{}",
        mcpMcporterJson: String = "{\"mcpServers\":{}}",
        webhookBaseUrl: String = "",
        awsAccessKeyId: String = "",
        awsSecretAccessKey: String = "",
        awsRegion: String = "us-east-1",
        awsBearerTokenBedrock: String = ""
    ): ContainerInfo {
        logger.info("Creating container for user $userId on host $hostIp with port $hostPort")

        // Ensure image exists on host
        ensureImageExists(hostIp)

        // Generate container name (sanitize userId for Docker naming rules)
        // Add random suffix to avoid collisions on retry after failed provisioning
        val sanitizedUserId = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(20)
        val randomSuffix = (100000..999999).random()
        val containerName = "openclaw-$sanitizedUserId-${System.currentTimeMillis()}-$randomSuffix"

        // Remove any stale containers for this user left from failed provisioning attempts
        sshCommandExecutor.runSshCommand(
            hostIp,
            "docker ps -a --filter 'name=openclaw-$sanitizedUserId-' --format '{{.ID}}' | xargs -r docker rm -f 2>/dev/null || true"
        )
        
        // Prepare MCP config JSON
        val mcpConfigJson = json.encodeToString(mapOf("tools" to mcpTools))
        
        // Build environment variables
        val envVars = buildEnvironmentVariables(
            gatewayToken = gatewayToken,
            hookToken = hookToken,
            supabaseConfig = supabaseConfig,
            mcpConfigJson = mcpConfigJson,
            userId = userId,
            mcpRoutesJson = mcpRoutesJson,
            mcpMcporterJson = mcpMcporterJson,
            webhookBaseUrl = webhookBaseUrl,
            awsAccessKeyId = awsAccessKeyId,
            awsSecretAccessKey = awsSecretAccessKey,
            awsRegion = awsRegion,
            awsBearerTokenBedrock = awsBearerTokenBedrock
        )
        
        // Build docker run command
        val dockerCommand = buildDockerRunCommand(
            containerName = containerName,
            sanitizedUserId = sanitizedUserId,
            hostPort = hostPort,
            envVars = envVars
        )
        
        // Execute docker run
        logger.debug("docker run command: $dockerCommand")
        val output = sshCommandExecutor.runSshCommand(hostIp, dockerCommand)
        val containerId = output.trim()
        
        if (containerId.isBlank() || containerId.length < 12) {
            throw IllegalStateException("Failed to create container. Output: $output")
        }
        
        logger.info("Container created: $containerId")
        
        // Wait briefly for container to start
        kotlinx.coroutines.delay(2000)
        
        // Verify container is running ("Up X seconds" from docker ps)
        val status = getContainerStatus(hostIp, containerId)
        if (!status.contains("up", ignoreCase = true)) {
            val logs = getContainerLogs(hostIp, containerId)
            runCatching { deleteContainer(hostIp, containerId) }
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
     * Checks if the configured OpenClaw image exists on the host, pulls it if not.
     */
    private suspend fun ensureImageExists(hostIp: String) {
        val image = AppConfig.dockerOpenclawImage
        val refreshLatestTag = image.substringAfterLast(':', "").equals("latest", ignoreCase = true) && !image.contains("@sha256:")

        if (refreshLatestTag) {
            logger.info("Refreshing latest OpenClaw image $image on $hostIp")
            sshCommandExecutor.runSshCommand(hostIp, "docker pull $image")
            return
        }

        val imageCheck = sshCommandExecutor.runSshCommand(
            hostIp,
            "docker images --format '{{.Repository}}:{{.Tag}}' | grep -F '${image.substringBefore(":")}' || echo 'NOT_FOUND'"
        ).trim()

        if (imageCheck == "NOT_FOUND") {
            logger.info("OpenClaw image $image not found on $hostIp. Pulling...")
            sshCommandExecutor.runSshCommand(hostIp, "docker pull $image")
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
        userId: String,
        mcpRoutesJson: String,
        mcpMcporterJson: String,
        webhookBaseUrl: String,
        awsAccessKeyId: String,
        awsSecretAccessKey: String,
        awsRegion: String,
        awsBearerTokenBedrock: String
    ): Map<String, String> {
        return mapOf(
            "GATEWAY_TOKEN" to gatewayToken,
            "HOOK_TOKEN" to hookToken,
            "SUPABASE_URL" to supabaseConfig.url,
            "SUPABASE_SERVICE_KEY" to supabaseConfig.serviceKey,
            "SUPABASE_PROJECT_REF" to supabaseConfig.projectRef,
            "SUPABASE_SCHEMA" to supabaseConfig.schema,
            "SUPABASE_API_KEY" to supabaseConfig.apiKey,
            "MCP_CONFIG_JSON" to mcpConfigJson,
            "MCP_ROUTES_JSON" to mcpRoutesJson,
            "MCP_MCPORTER_JSON" to mcpMcporterJson,
            "WEBHOOK_BASE_URL" to webhookBaseUrl,
            "AWS_ACCESS_KEY_ID" to awsAccessKeyId,
            "AWS_SECRET_ACCESS_KEY" to awsSecretAccessKey,
            "AWS_REGION" to awsRegion,
            "AWS_BEARER_TOKEN_BEDROCK" to awsBearerTokenBedrock,
            "USER_ID" to userId
        )
    }
    
    /**
     * Builds the docker run command with all necessary options.
     */
    private fun buildDockerRunCommand(
        containerName: String,
        sanitizedUserId: String,
        hostPort: Int,
        envVars: Map<String, String>
    ): String {
        val envArgs = envVars.entries.joinToString(" ") { (key, value) ->
            val escapedValue = value.replace("'", "'\\''")
            "-e $key='$escapedValue'"
        }

        return buildString {
            append("docker run -d")
            append(" --name $containerName")
            append(" --restart unless-stopped")
            append(" -p 127.0.0.1:$hostPort:$CONTAINER_PORT")
            append(" -v openclaw-devices-$sanitizedUserId:/home/openclaw/.openclaw/devices")
            append(" --memory=${AppConfig.dockerContainerMemory}")
            append(" --cpus=${AppConfig.dockerContainerCpu}")
            append(" --log-driver json-file")
            append(" --log-opt max-size=10m")
            append(" --log-opt max-file=3")
            append(" $envArgs")
            append(" ${AppConfig.dockerOpenclawImage}")
        }
    }
    
}
