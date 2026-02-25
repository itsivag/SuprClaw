package com.suprbeta.supabase

import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserAgent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.*

class SupabaseAgentRepository(
    private val application: Application
) {
    suspend fun saveAgent(client: SupabaseClient, agent: AgentInsert) {
        try {
            application.log.info("Saving agent: name=${agent.name}, role=${agent.role}")
            client.from("agents").insert(agent)
            application.log.info("Agent saved successfully: name=${agent.name}")
        } catch (e: Exception) {
            application.log.error("Failed to save agent name=${agent.name}", e)
            throw e
        }
    }

    suspend fun getAgents(client: SupabaseClient): List<UserAgent> {
        return try {
            application.log.debug("Fetching agents")
            client.from("agents").select().decodeList<UserAgent>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch agents", e)
            emptyList()
        }
    }

    suspend fun getAgentById(client: SupabaseClient, agentId: String): UserAgent? {
        return try {
            application.log.debug("Fetching agent by id=$agentId")
            client.from("agents").select {
                filter { eq("id", agentId) }
            }.decodeSingleOrNull<UserAgent>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch agent id=$agentId", e)
            null
        }
    }

    suspend fun deleteAgent(client: SupabaseClient, name: String) {
        try {
            application.log.info("Deleting agent: name=$name")
            client.from("agents").delete {
                filter { eq("name", name) }
            }
            application.log.info("Agent deleted successfully: name=$name")
        } catch (e: Exception) {
            application.log.error("Failed to delete agent name=$name", e)
            throw e
        }
    }
}
