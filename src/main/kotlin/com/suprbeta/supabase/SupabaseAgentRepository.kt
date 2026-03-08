package com.suprbeta.supabase

import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserAgent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.*
import kotlinx.coroutines.delay

private const val SCHEMA_CACHE_RETRY_ATTEMPTS = 6
private const val SCHEMA_CACHE_RETRY_INITIAL_DELAY_MS = 1_000L
private const val SCHEMA_CACHE_RETRY_MAX_DELAY_MS = 5_000L
internal val TRANSIENT_POSTGREST_CODES = setOf("PGRST002", "PGRST205")

internal fun isTransientPostgrestSchemaCacheError(error: Throwable): Boolean =
    error is PostgrestRestException && error.code in TRANSIENT_POSTGREST_CODES

internal suspend fun <T> retryTransientPostgrestSchemaCacheErrors(
    maxAttempts: Int = SCHEMA_CACHE_RETRY_ATTEMPTS,
    initialDelayMillis: Long = SCHEMA_CACHE_RETRY_INITIAL_DELAY_MS,
    onRetry: (attempt: Int, delayMillis: Long, error: PostgrestRestException) -> Unit = { _, _, _ -> },
    block: suspend () -> T
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }

    var delayMillis = initialDelayMillis
    repeat(maxAttempts - 1) { attemptIndex ->
        try {
            return block()
        } catch (error: PostgrestRestException) {
            if (!isTransientPostgrestSchemaCacheError(error)) throw error
            val attempt = attemptIndex + 1
            onRetry(attempt, delayMillis, error)
            delay(delayMillis)
            delayMillis = (delayMillis * 2).coerceAtMost(SCHEMA_CACHE_RETRY_MAX_DELAY_MS)
        }
    }

    return block()
}

class SupabaseAgentRepository(
    private val application: Application
) {
    suspend fun saveAgent(client: SupabaseClient, agent: AgentInsert) {
        try {
            application.log.info("Saving agent: name=${agent.name}, role=${agent.role}")
            retryTransientPostgrestSchemaCacheErrors(
                onRetry = { attempt, delayMillis, error ->
                    application.log.warn(
                        "PostgREST schema cache not ready while saving agent name=${agent.name} " +
                            "(code=${error.code}, attempt $attempt/$SCHEMA_CACHE_RETRY_ATTEMPTS); " +
                            "retrying in ${delayMillis}ms"
                    )
                }
            ) {
                client.from("agents").insert(agent)
            }
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

    suspend fun getLeadAgent(client: SupabaseClient): UserAgent? {
        return try {
            retryTransientPostgrestSchemaCacheErrors(
                onRetry = { attempt, delayMillis, error ->
                    application.log.warn(
                        "PostgREST schema cache not ready while fetching lead agent " +
                            "(code=${error.code}, attempt $attempt/$SCHEMA_CACHE_RETRY_ATTEMPTS); " +
                            "retrying in ${delayMillis}ms"
                    )
                }
            ) {
                client.from("agents").select {
                    filter { eq("is_lead", true) }
                }.decodeSingleOrNull<UserAgent>()
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch lead agent", e)
            null
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

            // Resolve agent id to unblock the FK constraint on tasks.locked_by
            val agent = client.from("agents").select {
                filter { eq("name", name) }
            }.decodeSingleOrNull<com.suprbeta.digitalocean.models.UserAgent>()

            if (agent?.id != null) {
                client.from("tasks").update({ set("locked_by", null as String?) }) {
                    filter { eq("locked_by", agent.id) }
                }
                application.log.info("Unlinked tasks locked by agent id=${agent.id}")
            }

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
