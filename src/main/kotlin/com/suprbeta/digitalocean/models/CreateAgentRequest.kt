package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateAgentRequest(
    val name: String
)
