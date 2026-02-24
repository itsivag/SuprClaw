package com.suprbeta.supabase

import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserAgent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.server.application.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAgentRepository(
    private val client: SupabaseClient,
    private val application: Application
) {

    suspend fun saveAgent(schemaName: String, agent: AgentInsert) {
        try {
            application.log.info("Saving agent: name=${agent.name}, role=${agent.role}, schema=$schemaName")
            client.postgrest.rpc("insert_user_agent", buildJsonObject {
                put("p_schema_name", schemaName)
                put("p_name", agent.name)
                put("p_role", agent.role)
                put("p_session_key", agent.sessionKey)
                put("p_is_lead", agent.isLead)
                put("p_status", agent.status)
            })
            application.log.info("Agent saved successfully: name=${agent.name}")
        } catch (e: Exception) {
            application.log.error("Failed to save agent name=${agent.name}", e)
            throw e
        }
    }

    suspend fun getAgents(schemaName: String): List<UserAgent> {
        return try {
            application.log.debug("Fetching agents for schema: $schemaName")
            client.postgrest.rpc("get_user_agents", buildJsonObject {
                put("p_schema_name", schemaName)
            }).decodeList<UserAgent>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch agents for schema $schemaName", e)
            emptyList()
        }
    }

    suspend fun deleteAgent(schemaName: String, name: String) {
        try {
            application.log.info("Deleting agent: name=$name, schema=$schemaName")
            client.postgrest.rpc("delete_user_agent", buildJsonObject {
                put("p_schema_name", schemaName)
                put("p_name", name)
            })
            application.log.info("Agent deleted successfully: name=$name")
        } catch (e: Exception) {
            application.log.error("Failed to delete agent name=$name", e)
            throw e
        }
    }
}
