package com.suprbeta.supabase

import kotlinx.serialization.Serializable

@Serializable
data class ProjectResult(
    val projectRef: String,
    val endpoint: String
)

interface SupabaseManagementService {
    /** Token or key used to identify/authenticate with the Supabase instance (written to VPS mcp.env). */
    val managementToken: String
    val webhookBaseUrl: String
    val webhookSecret: String
    suspend fun createProject(name: String): ProjectResult
    suspend fun waitForProjectActive(projectRef: String)
    suspend fun getServiceKey(projectRef: String): String
    suspend fun runSql(projectRef: String, sql: String)
    suspend fun createDatabaseWebhook(projectRef: String)
    suspend fun deleteProject(projectRef: String)
    /** For self-hosted: returns the schema name (== projectRef). */
    fun resolveSchema(projectRef: String): String
}
