package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateDropletRequest(
    val name: String,
    val size: String,
    val region: String,
    val image: String,
    val monitoring: Boolean,
    val vpc_uuid: String,
    val user_data: String? = null,
    val ssh_keys: List<String>? = null // SSH key IDs or fingerprints
)

