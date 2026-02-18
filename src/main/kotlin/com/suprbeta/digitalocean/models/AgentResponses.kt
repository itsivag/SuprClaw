package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentListResponse(
    val userId: String,
    val count: Int,
    val agents: List<UserAgent>
)

@Serializable
data class AgentMutationResponse(
    val dropletId: Long,
    val name: String,
    val role: String? = null,
    val message: String,
    val output: String
)
