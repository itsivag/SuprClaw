package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet

/**
 * Provisioning contract used by the API layer.
 *
 * SuprClaw now provisions PicoClaw environments through the shared-host Podman
 * implementation. The legacy dedicated droplet implementation has been removed.
 */
interface DropletProvisioningService {
    data class CreateResult(
        val dropletId: Long,
        val status: ProvisioningStatus,
        val password: String
    )

    suspend fun createAndProvision(name: String, userId: String): CreateResult

    suspend fun provisionDroplet(
        dropletId: Long,
        password: String,
        userId: String
    ): UserDroplet

    fun getStatus(dropletId: Long): ProvisioningStatus?

    fun getStatusForUser(dropletId: Long, userId: String): ProvisioningStatus?

    suspend fun teardown(userId: String)
}
