package com.suprbeta.podman

import com.suprbeta.config.AppConfig
import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.podman.models.*
import com.suprbeta.runtime.RuntimePaths
import io.ktor.server.application.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Service for managing Podman containers on host VPS instances.
 * 
 * This service creates, configures, and manages PicoClaw containers
 * via SSH commands to the host VPS.
 */
class PodmanContainerService(
    private val sshCommandExecutor: SshCommandExecutor,
    private val portAllocator: ContainerPortAllocator,
    private val application: Application
) {
    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    
    companion object {
        private const val CONTAINER_PORT = 18790
    }
    
    /**
     * Creates a new PicoClaw container on the specified host.
     * 
     * @param hostIp The host VPS IP address
     * @param userId The user ID for this container
     * @param gatewayToken Runtime authentication token
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
        supabaseConfig: SupabaseConfig,
        mcpTools: List<McpToolConfig>,
        hostPort: Int,
        mcpRoutesJson: String = "{}",
        mcpMcporterJson: String = "{\"mcpServers\":{}}",
        webhookBaseUrl: String = "",
        awsAccessKeyId: String = "",
        awsSecretAccessKey: String = "",
        awsRegion: String = "us-east-1",
        awsBearerTokenBedrock: String = "",
        liteLlmApiBase: String = "",
        liteLlmApiKey: String = "",
        liteLlmModelId: String = ""
    ): ContainerInfo {
        logger.info("Creating container for user $userId on host $hostIp with port $hostPort")

        // Ensure image exists on host
        ensureImageExists(hostIp)

        // Generate container name (sanitize userId for OCI naming rules)
        // Add random suffix to avoid collisions on retry after failed provisioning
        val sanitizedUserId = userId.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(20)
        val randomSuffix = (100000..999999).random()
        val containerName = "picoclaw-$sanitizedUserId-${System.currentTimeMillis()}-$randomSuffix"

        // Remove any stale containers for this user left from failed provisioning attempts
        sshCommandExecutor.runSshCommand(
            hostIp,
            "podman ps -a --filter 'name=picoclaw-$sanitizedUserId-' --format '{{.ID}}' | xargs -r podman rm -f 2>/dev/null || true"
        )
        
        // Prepare MCP config JSON
        val mcpConfigJson = json.encodeToString(mapOf("tools" to mcpTools))
        
        // Build environment variables
        val envVars = buildEnvironmentVariables(
            gatewayToken = gatewayToken,
            supabaseConfig = supabaseConfig,
            mcpConfigJson = mcpConfigJson,
            userId = userId,
            mcpRoutesJson = mcpRoutesJson,
            mcpMcporterJson = mcpMcporterJson,
            webhookBaseUrl = webhookBaseUrl,
            awsAccessKeyId = awsAccessKeyId,
            awsSecretAccessKey = awsSecretAccessKey,
            awsRegion = awsRegion,
            awsBearerTokenBedrock = awsBearerTokenBedrock,
            liteLlmApiBase = liteLlmApiBase,
            liteLlmApiKey = liteLlmApiKey,
            liteLlmModelId = liteLlmModelId
        )
        
        // Build podman run command
        val podmanCommand = buildPodmanRunCommand(
            containerName = containerName,
            sanitizedUserId = sanitizedUserId,
            hostPort = hostPort,
            envVars = envVars
        )

        // Execute podman run
        logger.debug("podman run command: $podmanCommand")
        val output = sshCommandExecutor.runSshCommand(hostIp, podmanCommand)
        val containerId = output.trim()
        
        if (containerId.isBlank() || containerId.length < 12) {
            throw IllegalStateException("Failed to create container. Output: $output")
        }
        
        logger.info("Container created: $containerId")
        
        // Wait briefly for container to start
        kotlinx.coroutines.delay(2000)
        
        // Verify container is running ("Up X seconds" from podman ps)
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
            status = ContainerInfo.STATUS_RUNNING,
            agentRuntime = "picoclaw"
        )
    }
    
    /**
     * Starts a stopped container.
     */
    suspend fun startContainer(hostIp: String, containerId: String) {
        logger.info("Starting container $containerId on $hostIp")
        sshCommandExecutor.runSshCommand(hostIp, "podman start $containerId")
    }
    
    /**
     * Stops a running container.
     */
    suspend fun stopContainer(hostIp: String, containerId: String) {
        logger.info("Stopping container $containerId on $hostIp")
        sshCommandExecutor.runSshCommand(hostIp, "podman stop $containerId")
    }
    
    /**
     * Deletes a container permanently.
     */
    suspend fun deleteContainer(hostIp: String, containerId: String) {
        logger.info("Deleting container $containerId on $hostIp")
        // Stop first (force if needed), then remove
        sshCommandExecutor.runSshCommand(hostIp, "podman rm -f $containerId 2>/dev/null || true")
    }
    
    /**
     * Gets the status of a container.
     */
    suspend fun getContainerStatus(hostIp: String, containerId: String): String {
        return try {
            sshCommandExecutor.runSshCommand(
                hostIp, 
                "podman ps --filter \"id=$containerId\" --format '{{.Status}}' 2>/dev/null || echo 'not found'"
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
                "podman logs --tail $tail $containerId 2>&1 || echo 'No logs available'"
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
            "podman exec $containerId $command"
        )
    }
    
    /**
     * Lists all containers on a host.
     */
    suspend fun listContainers(hostIp: String): List<String> {
        val output = sshCommandExecutor.runSshCommand(
            hostIp,
            "podman ps --format '{{.ID}}|{{.Names}}|{{.Status}}' 2>/dev/null || echo ''"
        )
        return output.lines().filter { it.isNotBlank() }
    }
    
    /**
     * Checks if the configured PicoClaw image exists on the host, pulls it if not.
     */
    private suspend fun ensureImageExists(hostIp: String) {
        val image = AppConfig.podmanPicoclawImage
        val buildContext = AppConfig.podmanPicoclawBuildContext
        val buildContainerfile = AppConfig.podmanPicoclawContainerfile
        val refreshLatestTag = image.substringAfterLast(':', "").equals("latest", ignoreCase = true) && !image.contains("@sha256:")
        val quotedImage = singleQuote(image)
        val quotedBuildContext = singleQuote(buildContext)

        if (refreshLatestTag) {
            logger.info("Refreshing PicoClaw image $image on $hostIp")
            runCatching {
                sshCommandExecutor.runSshCommand(hostIp, "podman pull $image")
            }.onFailure { error ->
                logger.warn("podman pull for $image failed on $hostIp: ${error.message}. Falling back to podman build from $buildContext")
                sshCommandExecutor.runSshCommand(
                    hostIp,
                    buildRemoteContextCommand(
                        image = quotedImage,
                        buildContext = quotedBuildContext,
                        containerfilePath = buildContainerfile
                    )
                )
            }
            return
        }

        val imageCheck = sshCommandExecutor.runSshCommand(
            hostIp,
            "podman image inspect $quotedImage >/dev/null 2>&1 && echo 'FOUND' || echo 'NOT_FOUND'"
        ).trim()

        if (imageCheck == "NOT_FOUND") {
            logger.info("PicoClaw image $image not found on $hostIp. Pulling...")
            runCatching {
                sshCommandExecutor.runSshCommand(hostIp, "podman pull $image")
            }.onFailure { error ->
                logger.warn("podman pull for $image failed on $hostIp: ${error.message}. Falling back to podman build from $buildContext")
                sshCommandExecutor.runSshCommand(
                    hostIp,
                    buildRemoteContextCommand(
                        image = quotedImage,
                        buildContext = quotedBuildContext,
                        containerfilePath = buildContainerfile
                    )
                )
            }
        }
    }

    private fun buildRemoteContextCommand(
        image: String,
        buildContext: String,
        containerfilePath: String
    ): String {
        val containerfile = Files.readString(Path.of(containerfilePath)).trimEnd()
        return buildString {
            append("podman build -t ")
            append(image)
            append(" -f- ")
            append(buildContext)
            append(" <<'SUPRCLAW_CONTAINERFILE'\n")
            append(containerfile)
            append("\nSUPRCLAW_CONTAINERFILE")
        }
    }
    
    /**
     * Builds environment variable arguments for podman run.
     */
    private fun buildEnvironmentVariables(
        gatewayToken: String,
        supabaseConfig: SupabaseConfig,
        mcpConfigJson: String,
        userId: String,
        mcpRoutesJson: String,
        mcpMcporterJson: String,
        webhookBaseUrl: String,
        awsAccessKeyId: String,
        awsSecretAccessKey: String,
        awsRegion: String,
        awsBearerTokenBedrock: String,
        liteLlmApiBase: String,
        liteLlmApiKey: String,
        liteLlmModelId: String
    ): Map<String, String> {
        return mapOf(
            "GATEWAY_TOKEN" to gatewayToken,
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
            "LITELLM_API_BASE" to liteLlmApiBase,
            "LITELLM_API_KEY" to liteLlmApiKey,
            "LITELLM_MODEL_ID" to liteLlmModelId,
            "USER_ID" to userId,
            "PICOCLAW_HOME" to RuntimePaths.runtimeHome,
            "PICOCLAW_CONFIG" to RuntimePaths.picoclawConfig
        )
    }
    
    /**
     * Builds the podman run command with all necessary options.
     */
    private fun buildPodmanRunCommand(
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
            append("podman run -d")
            append(" --replace")
            append(" --name $containerName")
            append(" --restart unless-stopped")
            append(" -p 127.0.0.1:$hostPort:$CONTAINER_PORT")
            append(" --memory=${AppConfig.podmanContainerMemory}")
            append(" $envArgs")
            append(" ${AppConfig.podmanPicoclawImage}")
        }
    }
    
}
