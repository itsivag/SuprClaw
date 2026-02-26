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

object McpToolRegistry {

    val tools: Map<String, McpToolDefinition> = mapOf(
        "supabase" to McpToolDefinition(
            name = "supabase",
            upstream = "https://mcp.supabase.com",
            authType = "bearer",
            authEnvVar = "SUPABASE_ACCESS_TOKEN",
            mcporterUrlTemplate = "http://127.0.0.1:18790/supabase/mcp?project_ref={projectRef}"
        ),
        "firecrawl" to McpToolDefinition(
            name = "firecrawl",
            upstream = "https://mcp.firecrawl.dev",
            authType = "path-prefix",
            authEnvVar = "FIRECRAWL_API_KEY",
            authTemplate = "/{key}/v2",
            mcporterUrlTemplate = "http://127.0.0.1:18790/firecrawl/mcp"
        )
    )

    /** Tools provisioned on every new VPS by default. */
    val defaultTools: List<String> = listOf("supabase")

    fun get(name: String): McpToolDefinition? = tools[name]
}
