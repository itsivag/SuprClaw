package com.suprbeta.digitalocean.models

import kotlinx.serialization.SerialName
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
    @SerialName("is_lead") val isLead: Boolean = false,
    val sessionKey: String? = null,
    val message: String,
    val output: String
)

@Serializable
data class AgentFileListResponse(
    val dropletId: Long,
    val agentName: String,
    val workspaceType: String,
    val files: List<String>
)

@Serializable
data class AgentFileContentResponse(
    val dropletId: Long,
    val agentName: String,
    val workspaceType: String,
    val fileName: String,
    val content: String
)
