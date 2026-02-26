package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.supabase.SupabaseManagementService
import io.ktor.server.application.*
import java.util.Base64

interface DropletMcpService {
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

class DropletMcpServiceImpl(
    private val managementService: SupabaseManagementService,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : DropletMcpService {

    private val logger = application.log

    override suspend fun configureMcpTools(droplet: UserDropletInternal, toolNames: List<String>) {
        val ip = droplet.ipAddress
        val sshKey = droplet.sshKey

        val toolDefs = toolNames.mapNotNull { name ->
            McpToolRegistry.get(name) ?: run {
                logger.warn("Unknown MCP tool '$name', skipping")
                null
            }
        }

        writeMcpEnv(ip, sshKey, droplet, toolDefs)
        writeMcpRoutes(ip, sshKey, toolDefs)
        writeMcporterConfig(ip, sshKey, toolDefs, droplet.supabaseProjectRef)

        // Restart mcporter if already running; silently skip if not yet started (provisioning).
        sshCommandExecutor.runSshCommand(ip, sshKey, "sudo systemctl restart mcporter 2>/dev/null || true")

        logger.info("MCP tools configured on droplet ${droplet.dropletId}: ${toolNames.joinToString()}")
    }

    // ── File writers ─────────────────────────────────────────────────────

    private fun writeMcpEnv(ip: String, sshKey: String, droplet: UserDropletInternal, toolDefs: List<McpToolDefinition>) {
        val alwaysPresent = setOf(
            "SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_REGION",
            "OPENCLAW_GATEWAY_TOKEN"
        )
        val lines = mutableListOf(
            "SUPABASE_PROJECT_REF=${droplet.supabaseProjectRef}",
            "SUPABASE_ACCESS_TOKEN=${managementService.managementToken}",
            "AWS_ACCESS_KEY_ID=${System.getenv("AWS_ACCESS_KEY_ID") ?: ""}",
            "AWS_SECRET_ACCESS_KEY=${System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""}",
            "AWS_REGION=${System.getenv("AWS_REGION") ?: "us-east-1"}",
            "OPENCLAW_GATEWAY_TOKEN=${droplet.gatewayToken}"
        )
        for (tool in toolDefs) {
            if (tool.authEnvVar !in alwaysPresent) {
                lines += "${tool.authEnvVar}=${System.getenv(tool.authEnvVar) ?: ""}"
            }
        }
        val encoded = Base64.getEncoder().encodeToString(lines.joinToString("\n").toByteArray())
        sshCommandExecutor.runSshCommand(ip, sshKey, "echo $encoded | base64 -d | sudo tee /etc/suprclaw/mcp.env > /dev/null")
        sshCommandExecutor.runSshCommand(ip, sshKey, "sudo chmod 600 /etc/suprclaw/mcp.env")
        sshCommandExecutor.runSshCommand(ip, sshKey, "sudo chown root:root /etc/suprclaw/mcp.env")
    }

    private fun writeMcpRoutes(ip: String, sshKey: String, toolDefs: List<McpToolDefinition>) {
        val routes = toolDefs.joinToString(",") { tool ->
            val auth = when (tool.authType) {
                "bearer" -> """{"type":"bearer","envVar":"${tool.authEnvVar}"}"""
                "path-prefix" -> """{"type":"path-prefix","envVar":"${tool.authEnvVar}","template":"${tool.authTemplate}"}"""
                else -> """{"type":"${tool.authType}","envVar":"${tool.authEnvVar}"}"""
            }
            """"${tool.name}":{"upstream":"${tool.upstream}","auth":$auth}"""
        }
        val json = "{$routes}"
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        sshCommandExecutor.runSshCommand(ip, sshKey, "echo $encoded | base64 -d | sudo tee /etc/suprclaw/mcp-routes.json > /dev/null")
        sshCommandExecutor.runSshCommand(ip, sshKey, "sudo chmod 600 /etc/suprclaw/mcp-routes.json")
        sshCommandExecutor.runSshCommand(ip, sshKey, "sudo chown root:root /etc/suprclaw/mcp-routes.json")
    }

    private fun writeMcporterConfig(ip: String, sshKey: String, toolDefs: List<McpToolDefinition>, projectRef: String) {
        val servers = toolDefs.joinToString(",") { tool ->
            val url = tool.mcporterUrlTemplate.replace("{projectRef}", projectRef)
            """"${tool.name}":{"url":"$url","lifecycle":"keep-alive"}"""
        }
        val json = """{"mcpServers":{$servers}}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        sshCommandExecutor.runSshCommand(ip, sshKey, "echo $encoded | base64 -d > /home/openclaw/.mcporter/mcporter.json")
    }
}
