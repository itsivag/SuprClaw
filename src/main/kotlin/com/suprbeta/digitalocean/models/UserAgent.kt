package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class UserAgent(
    val name: String = "",
    val workspacePath: String = "",
    val dropletId: Long = 0,
    val createdAt: String = ""
)
