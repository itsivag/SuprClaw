package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class UserAgent(
    val name: String = "",
    val agentId: String = "",
    val model: String = "google/gemini-2.5-flash",
    val workspacePath: String = "",
    val dropletId: Long = 0,
    val createdAt: String = ""
)
