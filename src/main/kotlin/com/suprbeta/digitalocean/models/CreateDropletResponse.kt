package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateDropletResponse(
    val droplet: DropletInfo,
    val setup_status_url: String,
    val message: String
)

@Serializable
data class DropletInfo(
    val id: Long?,
    val name: String,
    val status: String?,
    val ip_address: String? = null
)
