package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.json.JsonObject

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
}
