package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.runtime.RuntimePaths
import com.suprbeta.supabase.SupabaseManagementService
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

interface DropletMcpService {
    fun validateMcpTools(toolNames: List<String>)

    suspend fun configureMcpTools(
        droplet: UserDropletInternal,
        toolNames: List<String>,
        runtimeConfigByTool: Map<String, McpToolRuntimeConfig> = emptyMap()
    )
}

open class DropletMcpServiceImpl(
    private val managementService: SupabaseManagementService,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : DropletMcpService {

    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    private val json = Json { prettyPrint = true }

    internal open fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""

    companion object {
        private val ALWAYS_PRESENT_ENV_VARS = setOf(
            "SUPABASE_ACCESS_TOKEN",
            "GATEWAY_TOKEN"
        )
    }

    override fun validateMcpTools(toolNames: List<String>) {
        val missing = toolNames.mapNotNull { name ->
            val tool = McpToolRegistry.get(name) ?: return@mapNotNull null
            if (tool.authEnvVar !in ALWAYS_PRESENT_ENV_VARS && env(tool.authEnvVar).isBlank()) {
                "${tool.name} requires ${tool.authEnvVar}"
            } else {
                null
            }
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Cannot configure MCP tools: ${missing.joinToString()}")
        }
    }

    override suspend fun configureMcpTools(
        droplet: UserDropletInternal,
        toolNames: List<String>,
        runtimeConfigByTool: Map<String, McpToolRuntimeConfig>
    ) {
        val toolDefs = toolNames.distinct().mapNotNull { name ->
            McpToolRegistry.get(name) ?: run {
                logger.warn("Unknown MCP tool '$name', skipping")
                null
            }
        }
        val script = buildPicoclawConfigMutation(droplet, toolDefs, runtimeConfigByTool)
        runRemoteCommand(droplet, script)
        runRemoteCommand(droplet, "supervisorctl restart picoclaw-gateway >/dev/null 2>&1 || true")
        logger.info("Configured PicoClaw MCP servers on droplet ${droplet.dropletId}: ${toolDefs.joinToString { it.name }}")
    }

    internal fun generateScopedJwt(schemaName: String): String {
        val secret = env("SUPABASE_SELF_HOSTED_JWT_SECRET").ifBlank { return "" }
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val now = System.currentTimeMillis() / 1000
        val exp = now + 10L * 365 * 24 * 3600
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"role":"${schemaName}_rpc","iss":"supabase","iat":$now,"exp":$exp}""".toByteArray())
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val sig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$sig"
    }

    private fun buildPicoclawConfigMutation(
        droplet: UserDropletInternal,
        toolDefs: List<McpToolDefinition>,
        runtimeConfigByTool: Map<String, McpToolRuntimeConfig>
    ): String {
        val serverConfigs = toolDefs.associate { tool ->
            tool.name to buildServerConfig(droplet, tool, runtimeConfigByTool[tool.name])
        }
        val encodedServers = json.encodeToString(serverConfigs)
        val script = """
            const fs = require('fs');
            const path = ${json.encodeToString(RuntimePaths.picoclawConfig)};
            const cfg = fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, 'utf8')) : {};
            cfg.tools = cfg.tools || {};
            cfg.tools.mcp = cfg.tools.mcp || {};
            cfg.tools.mcp.discovery = cfg.tools.mcp.discovery || {};
            cfg.tools.mcp.discovery.enabled = false;
            cfg.tools.mcp.enabled = ${toolDefs.isNotEmpty()};
            cfg.tools.mcp.servers = ${encodedServers};
            fs.writeFileSync(path, JSON.stringify(cfg, null, 2));
        """.trimIndent()
        return "node -e ${singleQuote(script)}"
    }

    private fun buildServerConfig(
        droplet: UserDropletInternal,
        tool: McpToolDefinition,
        runtimeConfig: McpToolRuntimeConfig?
    ): JsonObject {
        val authValue = runtimeConfig?.authEnvValueOverride?.trim().takeUnless { it.isNullOrBlank() }
            ?: resolveAuthValue(droplet, tool)
        val upstream = runtimeConfig?.upstreamOverride?.trim().takeUnless { it.isNullOrBlank() }
            ?: tool.upstream

        return buildJsonObject {
            put("enabled", true)
            put("type", "http")
            put("url", upstream)
            if (tool.authType == "bearer") {
                putJsonObject("headers") {
                    put("Authorization", "Bearer $authValue")
                }
            }
        }
    }

    private fun resolveAuthValue(droplet: UserDropletInternal, tool: McpToolDefinition): String =
        when (tool.authEnvVar) {
            "GATEWAY_TOKEN" -> droplet.gatewayToken
            "SUPABASE_ACCESS_TOKEN" -> generateScopedJwt(droplet.supabaseProjectRef).ifBlank {
                managementService.managementToken
            }
            else -> env(tool.authEnvVar)
        }

    private fun runRemoteCommand(droplet: UserDropletInternal, command: String): String {
        val remoteCommand = if (droplet.isDockerDeployment()) {
            val containerId = droplet.containerIdOrNull()
                ?: throw IllegalStateException("Missing container ID for docker deployment")
            "docker exec ${singleQuote(containerId)} sh -lc ${singleQuote(command)}"
        } else {
            command
        }
        return sshCommandExecutor.runSshCommand(droplet.ipAddress, remoteCommand)
    }
}
