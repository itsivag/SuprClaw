package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.supabase.SupabaseManagementService
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import java.util.Base64

interface DropletMcpService {
    /**
     * Throws if any tool in [toolNames] is missing its required backend env var.
     * Call this before attempting installation so the error is caught early.
     */
    fun validateMcpTools(toolNames: List<String>)

    /**
     * Writes mcp.env, mcp-routes.json, and mcporter.json for the given tool names.
     * In Docker mode this updates the tenant container; in VPS mode it updates the host VM.
     *
     * This is idempotent: pass the full desired set of tools each time.
     * During initial provisioning the restart is a no-op if services are not started yet.
     */
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

    internal open fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""

    companion object {
        val ALWAYS_PRESENT_ENV_VARS = setOf(
            "SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_REGION", "AWS_BEARER_TOKEN_BEDROCK",
            "OPENCLAW_GATEWAY_TOKEN"
        )
    }

    override fun validateMcpTools(toolNames: List<String>) {
        val missing = toolNames.mapNotNull { name ->
            val tool = McpToolRegistry.get(name) ?: return@mapNotNull null
            if (tool.authEnvVar !in ALWAYS_PRESENT_ENV_VARS && isMissingEnvVar(tool.authEnvVar))
                "${tool.name} requires ${tool.authEnvVar}"
            else null
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot configure MCP tools — missing env vars: ${missing.joinToString()}"
            )
        }
    }

    private fun isMissingEnvVar(key: String): Boolean = env(key).isBlank()

    override suspend fun configureMcpTools(
        droplet: UserDropletInternal,
        toolNames: List<String>,
        runtimeConfigByTool: Map<String, McpToolRuntimeConfig>
    ) {
        val toolDefs = toolNames.mapNotNull { name ->
            McpToolRegistry.get(name) ?: run {
                logger.warn("Unknown MCP tool '$name', skipping")
                null
            }
        }
        val authEnvValueOverrides = runtimeConfigByTool.mapNotNull { (toolName, config) ->
            val tool = McpToolRegistry.get(toolName) ?: return@mapNotNull null
            val authValue = config.authEnvValueOverride?.trim()
            if (authValue.isNullOrEmpty()) null else tool.authEnvVar to authValue
        }.toMap()

        writeMcpEnv(droplet, toolDefs, authEnvValueOverrides)
        writeMcpRoutes(droplet, toolDefs, runtimeConfigByTool)
        writeMcporterConfig(droplet, toolDefs, droplet.supabaseProjectRef)

        // Refresh the proxy script so runtime updates do not depend on a full image rebuild.
        val proxyJs = javaClass.getResourceAsStream("/mcp-auth-proxy.js")!!
            .bufferedReader().readText()
        writeProxyScript(droplet, proxyJs)
        restartMcpRuntime(droplet)

        logger.info("MCP tools configured on droplet ${droplet.dropletId}: ${toolNames.joinToString()}")
    }

    suspend fun configureMcpTools(droplet: UserDropletInternal, toolNames: List<String>) {
        configureMcpTools(droplet, toolNames, emptyMap())
    }

    // ── File writers ─────────────────────────────────────────────────────

    private fun writeMcpEnv(
        droplet: UserDropletInternal,
        toolDefs: List<McpToolDefinition>,
        authEnvValueOverrides: Map<String, String>
    ) {
        val scopedAccessToken = generateScopedJwt(droplet.supabaseProjectRef)
            .ifBlank { managementService.managementToken }

        val lines = mutableListOf(
            "SUPABASE_PROJECT_REF=${droplet.supabaseProjectRef}",
            "SUPABASE_ACCESS_TOKEN=$scopedAccessToken",
            "AWS_ACCESS_KEY_ID=${env("AWS_ACCESS_KEY_ID")}",
            "AWS_SECRET_ACCESS_KEY=${env("AWS_SECRET_ACCESS_KEY")}",
            "AWS_REGION=${env("AWS_REGION").ifBlank { "us-east-1" }}",
            "AWS_BEARER_TOKEN_BEDROCK=${env("AWS_BEARER_TOKEN_BEDROCK")}",
            "OPENCLAW_GATEWAY_TOKEN=${droplet.gatewayToken}",
            "SUPABASE_API_KEY=${env("SUPABASE_SELF_HOSTED_SERVICE_KEY")}"
        )
        for (tool in toolDefs) {
            if (tool.authEnvVar !in ALWAYS_PRESENT_ENV_VARS) {
                val value = authEnvValueOverrides[tool.authEnvVar] ?: env(tool.authEnvVar)
                lines += "${tool.authEnvVar}=$value"
            }
        }
        writeProtectedFile(droplet, "/etc/suprclaw/mcp.env", lines.joinToString("\n"))
    }

    private fun writeMcpRoutes(
        droplet: UserDropletInternal,
        toolDefs: List<McpToolDefinition>,
        runtimeConfigByTool: Map<String, McpToolRuntimeConfig>
    ) {
        val routes = toolDefs.joinToString(",") { tool ->
            val auth = when (tool.authType) {
                "bearer" -> """{"type":"bearer","envVar":"${tool.authEnvVar}"}"""
                "path-prefix" -> """{"type":"path-prefix","envVar":"${tool.authEnvVar}","template":"${tool.authTemplate}"}"""
                else -> """{"type":"${tool.authType}","envVar":"${tool.authEnvVar}"}"""
            }
            val upstream = runtimeConfigByTool[tool.name]?.upstreamOverride?.trim()?.ifBlank { null }
                ?: if (tool.name == "supabase") env("SUPABASE_MCP_URL").ifBlank { tool.upstream } else tool.upstream
            """"${tool.name}":{"upstream":"$upstream","auth":$auth}"""
        }
        writeProtectedFile(droplet, "/etc/suprclaw/mcp-routes.json", "{$routes}")
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

    private fun writeMcporterConfig(droplet: UserDropletInternal, toolDefs: List<McpToolDefinition>, projectRef: String) {
        val servers = toolDefs.joinToString(",") { tool ->
            val url = tool.mcporterUrlTemplate.replace("{projectRef}", projectRef)
            """"${tool.name}":{"url":"$url","lifecycle":"keep-alive"}"""
        }
        val json = """{"mcpServers":{$servers}}"""
        writeOpenClawFile(droplet, "/home/openclaw/.mcporter/mcporter.json", json)
        writeOpenClawFile(droplet, "/home/openclaw/config/mcporter.json", json)
    }

    private fun writeProxyScript(droplet: UserDropletInternal, proxyJs: String) {
        if (droplet.isDockerDeployment()) {
            writeProtectedFile(droplet, "/usr/local/bin/mcp-auth-proxy.js", proxyJs, mode = "700")
            return
        }

        val proxyEncoded = Base64.getEncoder().encodeToString(proxyJs.toByteArray())
        sshCommandExecutor.runSshCommand(
            droplet.ipAddress,
            "echo $proxyEncoded | base64 -d | sudo tee /usr/local/bin/mcp-auth-proxy.js > /dev/null"
        )
        sshCommandExecutor.runSshCommand(
            droplet.ipAddress,
            "sudo chmod 700 /usr/local/bin/mcp-auth-proxy.js && sudo chown root:root /usr/local/bin/mcp-auth-proxy.js"
        )
    }

    private fun restartMcpRuntime(droplet: UserDropletInternal) {
        if (droplet.isDockerDeployment()) {
            runRemoteCommand(droplet, "supervisorctl restart mcp-auth-proxy >/dev/null 2>&1 || true")
            runRemoteCommand(droplet, "mcporter daemon restart >/dev/null 2>&1 || mcporter daemon start >/dev/null 2>&1 || true")
            return
        }

        // Restart mcporter if already running; silently skip if not yet started (provisioning).
        sshCommandExecutor.runSshCommand(droplet.ipAddress, "sudo systemctl restart mcporter 2>/dev/null || true")
        sshCommandExecutor.runSshCommand(droplet.ipAddress, "sudo systemctl restart mcp-auth-proxy")
    }

    private fun writeProtectedFile(
        droplet: UserDropletInternal,
        path: String,
        content: String,
        mode: String = "600"
    ) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        if (droplet.isDockerDeployment()) {
            val parent = path.substringBeforeLast('/')
            runRemoteCommand(
                droplet,
                "mkdir -p ${singleQuote(parent)} && " +
                    "echo $encoded | base64 -d > ${singleQuote(path)} && " +
                    "chmod $mode ${singleQuote(path)} && " +
                    "chown root:root ${singleQuote(path)}"
            )
            return
        }

        sshCommandExecutor.runSshCommand(
            droplet.ipAddress,
            "echo $encoded | base64 -d | sudo tee ${singleQuote(path)} > /dev/null"
        )
        sshCommandExecutor.runSshCommand(droplet.ipAddress, "sudo chmod $mode ${singleQuote(path)}")
        sshCommandExecutor.runSshCommand(droplet.ipAddress, "sudo chown root:root ${singleQuote(path)}")
    }

    private fun writeOpenClawFile(droplet: UserDropletInternal, path: String, content: String) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        val parent = path.substringBeforeLast('/')
        if (droplet.isDockerDeployment()) {
            runRemoteCommand(
                droplet,
                "mkdir -p ${singleQuote(parent)} && " +
                    "echo $encoded | base64 -d > ${singleQuote(path)} && " +
                    "chown openclaw:openclaw ${singleQuote(path)} && " +
                    "chmod 644 ${singleQuote(path)}"
            )
            return
        }

        sshCommandExecutor.runSshCommand(droplet.ipAddress, "mkdir -p ${singleQuote(parent)}")
        sshCommandExecutor.runSshCommand(droplet.ipAddress, "echo $encoded | base64 -d > ${singleQuote(path)}")
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
