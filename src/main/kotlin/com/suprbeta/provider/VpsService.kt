package com.suprbeta.provider

/**
 * Cloud provider abstraction for VPS/server lifecycle management.
 * Implementations include DigitalOceanService and HetznerService.
 */
interface VpsService {
    /**
     * Creates a new server with bootstrap user-data for the given password.
     * @return result containing the new server ID
     */
    suspend fun createServer(name: String, password: String): ServerCreateResult

    /**
     * Retrieves server status and network information.
     * Implementations must normalize status to "active" when the server is ready.
     */
    suspend fun getServer(serverId: Long): ServerInfo

    /**
     * Permanently deletes a server by ID.
     */
    suspend fun deleteServer(serverId: Long)

    data class ServerCreateResult(val serverId: Long)

    data class ServerInfo(
        /** Normalized status: "active" means the server is ready (maps from DO "active", Hetzner "running"). */
        val status: String,
        val publicIpV4: String?
    )
}
