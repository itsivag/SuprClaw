package com.suprbeta.digitalocean.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserAgent(
    val id: String? = null,
    val name: String = "",
    val role: String = "",
    @SerialName("session_key") val sessionKey: String = "",
    @SerialName("is_lead") val isLead: Boolean = false,
    val status: String = "active",
    @SerialName("current_task") val currentTask: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class AgentInsert(
    val name: String,
    val role: String,
    @SerialName("session_key") val sessionKey: String,
    @SerialName("is_lead") val isLead: Boolean = false,
    val status: String = "active",
    @SerialName("current_task") val currentTask: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null
)
