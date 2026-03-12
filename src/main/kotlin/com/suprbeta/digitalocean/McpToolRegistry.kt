package com.suprbeta.digitalocean

/**
 * Defines an MCP server that the universal proxy knows how to route to.
 *
 * authType values:
 *   "bearer"      — injects Authorization: Bearer <envVar> header
 *   "path-prefix" — prepends authTemplate (with {key} replaced) to the upstream path
 *
 * mcporterUrlTemplate supports {projectRef} substitution.
 */
data class McpToolDefinition(
    val name: String,
    val upstream: String,
    val authType: String,
    val authEnvVar: String,
    val authTemplate: String? = null,
    val mcporterUrlTemplate: String
)

data class McpToolRuntimeConfig(
    val upstreamOverride: String? = null,
    val authEnvValueOverride: String? = null
)

object McpToolRegistry {

    val tools: Map<String, McpToolDefinition> = mapOf(
        "supabase" to McpToolDefinition(
            name = "supabase",
            upstream = "https://supabase.suprclaw.com",
            authType = "bearer",
            authEnvVar = "SUPABASE_ACCESS_TOKEN",
            mcporterUrlTemplate = "http://127.0.0.1:18790/supabase/mcp?project_ref={projectRef}"
        ),
        "cloud_browser" to McpToolDefinition(
            name = "cloud_browser",
            upstream = "https://api.suprclaw.com/api/mcp/cloud-browser",
            authType = "bearer",
            authEnvVar = "OPENCLAW_GATEWAY_TOKEN",
            mcporterUrlTemplate = "http://127.0.0.1:18790/cloud_browser"
        ),
        "zapier" to McpToolDefinition(
            name = "zapier",
            upstream = "https://mcp.zapier.com",
            authType = "bearer",
            authEnvVar = "ZAPIER_MCP_EMBED_SECRET",
            // Zapier embed returns a full per-user MCP server URL; avoid appending an extra fixed path.
            mcporterUrlTemplate = "http://127.0.0.1:18790/zapier"
        )
    )

    /** Tools provisioned on every new VPS by default. */
    val defaultTools: List<String> = listOf("supabase", "cloud_browser")

    fun get(name: String): McpToolDefinition? = tools[name]
}
