package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateDropletNameRequest(
    val name: String
)
