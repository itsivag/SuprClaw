package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class ProvisioningStatus(
    val droplet_id: Long,
    val droplet_name: String,
    val phase: String,
    val progress: Double = 0.0, // 0.0 to 1.0 progress indicator
    val ip_address: String? = null,
    val subdomain: String? = null,
    val gateway_port: Int = 18789,
    val user: String = "openclaw",
    val gateway_token: String? = null,
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
        const val PHASE_DNS = "configuring_dns"
        const val PHASE_VERIFYING = "verifying"
        const val PHASE_NGINX = "configuring_nginx"
        const val PHASE_COMPLETE = "complete"
        const val PHASE_FAILED = "failed"
    }
}
