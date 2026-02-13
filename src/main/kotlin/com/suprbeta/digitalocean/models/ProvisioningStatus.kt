package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class ProvisioningStatus(
    val droplet_id: Long,
    val droplet_name: String,
    val phase: String,
    val ip_address: String? = null,
    val gateway_port: Int = 18789,
    val user: String = "openclaw",
    val message: String,
    val error: String? = null,
    val started_at: String,
    val completed_at: String? = null
) {
    companion object {
        const val PHASE_CREATING = "creating"
        const val PHASE_WAITING_ACTIVE = "waiting_active"
        const val PHASE_WAITING_SSH = "waiting_ssh"
        const val PHASE_ONBOARDING = "onboarding"
        const val PHASE_CONFIGURING = "configuring"
        const val PHASE_VERIFYING = "verifying"
        const val PHASE_COMPLETE = "complete"
        const val PHASE_FAILED = "failed"
    }
}
