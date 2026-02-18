package com.suprbeta.supabase

import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserAgent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.*

class SupabaseAgentRepository(
    private val client: SupabaseClient,
    private val application: Application
) {
    companion object {
        private const val TABLE = "agents"
    }

    suspend fun saveAgent(agent: AgentInsert) {
        try {
            application.log.info("Saving agent: name=${agent.name}, role=${agent.role}")
            client.from(TABLE).insert(agent)
            application.log.info("Agent saved successfully: name=${agent.name}")
        } catch (e: Exception) {
            application.log.error("Failed to save agent name=${agent.name}", e)
            throw e
        }
    }

    suspend fun getAgents(): List<UserAgent> {
        return try {
            application.log.debug("Fetching all agents")
            client.from(TABLE).select().decodeList<UserAgent>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch agents", e)
            emptyList()
        }
    }

    suspend fun deleteAgent(name: String) {
        try {
            application.log.info("Deleting agent: name=$name")
            client.from(TABLE).delete {
                filter {
                    eq("name", name)
                }
            }
            application.log.info("Agent deleted successfully: name=$name")
        } catch (e: Exception) {
            application.log.error("Failed to delete agent name=$name", e)
            throw e
        }
    }
}
