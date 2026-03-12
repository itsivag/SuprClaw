package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NotificationRepository(
    private val application: Application
) {
    suspend fun getNotifications(client: SupabaseClient): List<JsonObject> {
        return try {
            application.log.debug("Fetching notifications")
            client.from("notifications").select().decodeList<JsonObject>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch notifications", e)
            emptyList()
        }
    }

    suspend fun createNotification(
        client: SupabaseClient,
        type: String,
        payload: JsonObject,
        agentId: String? = null
    ) {
        try {
            client.from("notifications").insert(
                buildJsonObject {
                    agentId?.takeIf { it.isNotBlank() }?.let { put("agent_id", it) }
                    put("type", type)
                    put("payload", payload)
                }
            )
        } catch (e: Exception) {
            application.log.error("Failed to create notification type=$type", e)
            throw e
        }
    }
}
