package com.suprbeta.digitalocean

/**
 * Defines an MCP server that PicoClaw can connect to directly.
 *
 * authType values:
 *   "bearer" — inject Authorization: Bearer <envVar> into the remote HTTP transport
 */
data class McpToolDefinition(
    val name: String,
    val upstream: String,
    val authType: String,
    val authEnvVar: String
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
            authEnvVar = "SUPABASE_ACCESS_TOKEN"
        ),
        "cloud_browser" to McpToolDefinition(
            name = "cloud_browser",
            upstream = "https://api.suprclaw.com/api/mcp/cloud-browser",
            authType = "bearer",
            authEnvVar = "GATEWAY_TOKEN"
        ),
        "zapier" to McpToolDefinition(
            name = "zapier",
            upstream = "https://mcp.zapier.com",
            authType = "bearer",
            authEnvVar = "ZAPIER_MCP_EMBED_SECRET"
        )
    )

    /** Tools provisioned on every new VPS by default. */
    val defaultTools: List<String> = listOf("supabase", "cloud_browser")

    fun get(name: String): McpToolDefinition? = tools[name]
}
