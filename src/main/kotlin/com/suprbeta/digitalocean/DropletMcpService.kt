package com.suprbeta.digitalocean

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
     * Writes mcp.env, mcp-routes.json, and mcporter.json to the VPS for the given
     * tool names, then restarts mcporter.
     *
     * This is idempotent: pass the full desired set of tools each time.
     * During initial provisioning the mcporter restart is a no-op (services
     * not started yet); the provisioning caller starts them afterward.
     */
    suspend fun configureMcpTools(droplet: UserDropletInternal, toolNames: List<String>)
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

    override suspend fun configureMcpTools(droplet: UserDropletInternal, toolNames: List<String>) {
        val ip = droplet.ipAddress

        val toolDefs = toolNames.mapNotNull { name ->
            McpToolRegistry.get(name) ?: run {
                logger.warn("Unknown MCP tool '$name', skipping")
                null
            }
        }

        writeMcpEnv(ip, droplet, toolDefs)
        writeMcpRoutes(ip, toolDefs)
        writeMcporterConfig(ip, toolDefs, droplet.supabaseProjectRef)

        // Restart mcporter if already running; silently skip if not yet started (provisioning).
        sshCommandExecutor.runSshCommand(ip, "sudo systemctl restart mcporter 2>/dev/null || true")

        logger.info("MCP tools configured on droplet ${droplet.dropletId}: ${toolNames.joinToString()}")
    }

    // ── File writers ─────────────────────────────────────────────────────

    private fun writeMcpEnv(ip: String, droplet: UserDropletInternal, toolDefs: List<McpToolDefinition>) {
        val lines = mutableListOf(
            "SUPABASE_PROJECT_REF=${droplet.supabaseProjectRef}",
            "SUPABASE_ACCESS_TOKEN=${managementService.managementToken}",
            "AWS_ACCESS_KEY_ID=${env("AWS_ACCESS_KEY_ID")}",
            "AWS_SECRET_ACCESS_KEY=${env("AWS_SECRET_ACCESS_KEY")}",
            "AWS_REGION=${env("AWS_REGION").ifBlank { "us-east-1" }}",
            "AWS_BEARER_TOKEN_BEDROCK=${env("AWS_BEARER_TOKEN_BEDROCK")}",
            "OPENCLAW_GATEWAY_TOKEN=${droplet.gatewayToken}"
        )
        for (tool in toolDefs) {
            if (tool.authEnvVar !in ALWAYS_PRESENT_ENV_VARS) {
                lines += "${tool.authEnvVar}=${env(tool.authEnvVar)}"
            }
        }
        val encoded = Base64.getEncoder().encodeToString(lines.joinToString("\n").toByteArray())
        sshCommandExecutor.runSshCommand(ip, "echo $encoded | base64 -d | sudo tee /etc/suprclaw/mcp.env > /dev/null")
        sshCommandExecutor.runSshCommand(ip, "sudo chmod 600 /etc/suprclaw/mcp.env")
        sshCommandExecutor.runSshCommand(ip, "sudo chown root:root /etc/suprclaw/mcp.env")
    }

    private fun writeMcpRoutes(ip: String, toolDefs: List<McpToolDefinition>) {
        val selfHostedMcp = env("SUPABASE_MCP_URL").isNotBlank()
        val routes = toolDefs
            .filter { !(it.name == "supabase" && selfHostedMcp) }
            .joinToString(",") { tool ->
                val auth = when (tool.authType) {
                    "bearer" -> """{"type":"bearer","envVar":"${tool.authEnvVar}"}"""
                    "path-prefix" -> """{"type":"path-prefix","envVar":"${tool.authEnvVar}","template":"${tool.authTemplate}"}"""
                    else -> """{"type":"${tool.authType}","envVar":"${tool.authEnvVar}"}"""
                }
                val upstream = if (tool.name == "supabase") env("SUPABASE_MCP_URL").ifBlank { tool.upstream } else tool.upstream
                """"${tool.name}":{"upstream":"$upstream","auth":$auth}"""
            }
        val json = "{$routes}"
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        sshCommandExecutor.runSshCommand(ip, "echo $encoded | base64 -d | sudo tee /etc/suprclaw/mcp-routes.json > /dev/null")
        sshCommandExecutor.runSshCommand(ip, "sudo chmod 600 /etc/suprclaw/mcp-routes.json")
        sshCommandExecutor.runSshCommand(ip, "sudo chown root:root /etc/suprclaw/mcp-routes.json")
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

    private fun writeMcporterConfig(ip: String, toolDefs: List<McpToolDefinition>, projectRef: String) {
        val selfHostedMcp = env("SUPABASE_MCP_URL").isNotBlank()
        val servers = toolDefs.joinToString(",") { tool ->
            if (tool.name == "supabase" && selfHostedMcp) {
                val supabaseUrl = env("SUPABASE_SELF_HOSTED_URL")
                val scopedJwt = generateScopedJwt(projectRef)
                """"supabase":{"command":"npx","args":["-y","@supabase/mcp-server-supabase@latest"],"env":{"SUPABASE_URL":"$supabaseUrl","SUPABASE_ACCESS_TOKEN":"$scopedJwt"}}"""
            } else {
                val url = tool.mcporterUrlTemplate.replace("{projectRef}", projectRef)
                """"${tool.name}":{"url":"$url","lifecycle":"keep-alive"}"""
            }
        }
        val json = """{"mcpServers":{$servers}}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        sshCommandExecutor.runSshCommand(ip, "echo $encoded | base64 -d > /home/openclaw/.mcporter/mcporter.json")
        sshCommandExecutor.runSshCommand(ip, "echo $encoded | base64 -d > /home/openclaw/config/mcporter.json")
    }
}
