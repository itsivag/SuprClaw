package com.suprbeta.infra

/**
 * Provider-agnostic interface for VPS compute operations.
 * Implementations: DigitalOceanVpsProvider, HetznerVpsProvider
 */
interface VpsProvider {

    /** Returned after a successful create call. */
    data class VpsCreateResult(val id: Long)

    /**
     * Normalized server state.
     * [status] is always "active" when ready, regardless of what the underlying
     * provider calls it (DO "active", Hetzner "running", etc.).
     */
    data class VpsState(val status: String, val publicIpv4: String?)

    /** Create a new VPS with the given name and inject [password] via cloud-init. */
    suspend fun create(name: String, password: String): VpsCreateResult

    /** Return the current state of a VPS by its [id]. */
    suspend fun getState(id: Long): VpsState

    /** Permanently delete a VPS by its [id]. */
    suspend fun delete(id: Long)
}
