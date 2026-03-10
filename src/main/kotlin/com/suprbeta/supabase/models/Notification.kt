package com.suprbeta.supabase.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NotificationListResponse(
    val count: Int,
    val notifications: List<JsonObject>
)
