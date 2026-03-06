package com.suprbeta.docker

import com.suprbeta.docker.models.HostInfo
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.provider.VpsService
import io.ktor.server.application.*
import java.time.Instant

/**
 * Manages a pool of host VPS instances for running containers.
 * 
 * This service:
 * - Tracks available capacity on each host
 * - Assigns users to hosts with available capacity
 * - Creates new hosts when all existing hosts are full
 */
class HostPoolManager(
    private val vpsService: VpsService,
    private val firestoreRepository: FirestoreRepository,
    private val application: Application
) {
    private val logger = application.log
    
    companion object {
        private const val DEFAULT_HOST_CAPACITY = 20
        private const val HOST_NAME_PREFIX = "suprclaw-host"
    }
    
    /**
     * Gets an existing host with available capacity or creates a new one.
     * 
     * @return Pair of (hostId, hostIp)
     */
    suspend fun getOrCreateHostForNewUser(): Pair<Long, String> {
        logger.info("Finding host with available capacity...")
        
        // First, try to find an existing host with capacity
        val existingHost = findHostWithCapacity()
        
        if (existingHost != null) {
            logger.info("Found existing host: ${existingHost.hostId} at ${existingHost.hostIp} " +
                "(capacity: ${existingHost.availableCapacity}/${existingHost.totalCapacity})")
            return Pair(existingHost.hostId, existingHost.hostIp)
        }
        
        // No host with capacity found, create a new one
        logger.info("No hosts with available capacity. Creating new host...")
        return createNewHost()
    }
    
    /**
     * Assigns a user to a specific host (atomic counter increment + mapping write).
     */
    suspend fun assignUserToHost(userId: String, hostId: Long) {
        logger.info("Assigning user $userId to host $hostId")
        firestoreRepository.incrementHostContainerCount(hostId, DEFAULT_HOST_CAPACITY)
        firestoreRepository.saveUserHostMapping(userId, hostId)
    }

    /**
     * Releases a user from a host (atomic counter decrement + mapping delete).
     */
    suspend fun releaseUserFromHost(userId: String, hostId: Long) {
        logger.info("Releasing user $userId from host $hostId")
        firestoreRepository.decrementHostContainerCount(hostId)
        firestoreRepository.deleteUserHostMapping(userId)
    }
    
    /**
     * Gets host information for a user.
     */
    suspend fun getHostForUser(userId: String): HostInfo? {
        val hostId = firestoreRepository.getUserHostMapping(userId)
        return hostId?.let { firestoreRepository.getHostInfo(it) }
    }
    
    /**
     * Gets host information by ID.
     */
    suspend fun getHostInfo(hostId: Long): HostInfo? {
        return firestoreRepository.getHostInfo(hostId)
    }
    
    /**
     * Lists all active hosts.
     */
    suspend fun listActiveHosts(): List<HostInfo> {
        return firestoreRepository.listHosts().filter { 
            it.status == HostInfo.STATUS_ACTIVE 
        }
    }
    
    /**
     * Updates host capacity (e.g., after adding more resources).
     */
    suspend fun updateHostCapacity(hostId: Long, newCapacity: Int) {
        logger.info("Updating host $hostId capacity to $newCapacity")
        
        val hostInfo = firestoreRepository.getHostInfo(hostId)
        if (hostInfo != null) {
            val updatedHost = hostInfo.copy(
                totalCapacity = newCapacity,
                status = if (hostInfo.currentContainers >= newCapacity) {
                    HostInfo.STATUS_FULL
                } else {
                    HostInfo.STATUS_ACTIVE
                }
            )
            firestoreRepository.saveHostInfo(updatedHost)
        }
    }
    
    /**
     * Marks a host for maintenance.
     */
    suspend fun setHostMaintenance(hostId: Long, inMaintenance: Boolean) {
        logger.info("Setting host $hostId maintenance mode: $inMaintenance")
        
        val hostInfo = firestoreRepository.getHostInfo(hostId)
        if (hostInfo != null) {
            val updatedHost = hostInfo.copy(
                status = if (inMaintenance) HostInfo.STATUS_MAINTENANCE else HostInfo.STATUS_ACTIVE
            )
            firestoreRepository.saveHostInfo(updatedHost)
        }
    }
    
    /**
     * Finds an existing host with available capacity.
     */
    private suspend fun findHostWithCapacity(): HostInfo? {
        val hosts = firestoreRepository.listHosts()
        
        // Sort by most available capacity (fill hosts evenly)
        return hosts
            .filter { it.status == HostInfo.STATUS_ACTIVE && !it.isFull }
            .maxByOrNull { it.availableCapacity }
    }
    
    /**
     * Creates a new host VPS.
     */
    private suspend fun createNewHost(): Pair<Long, String> {
        val hostName = "$HOST_NAME_PREFIX-${System.currentTimeMillis()}"
        val password = generatePassword()
        
        logger.info("Creating new host VPS: $hostName")
        
        // Create server via VPS provider
        val result = vpsService.createServer(hostName, password)
        val hostId = result.serverId
        
        // Wait for server to be active and get IP
        val serverInfo = waitForServerActive(hostId)
        val hostIp = serverInfo.publicIpV4
            ?: throw IllegalStateException("Server $hostId has no public IP")
        
        // Save host info to Firestore
        val hostInfo = HostInfo(
            hostId = hostId,
            hostIp = hostIp,
            totalCapacity = DEFAULT_HOST_CAPACITY,
            currentContainers = 0,
            status = HostInfo.STATUS_ACTIVE,
            createdAt = Instant.now().toString(),
            region = "nbg1" // TODO: Make configurable
        )
        firestoreRepository.saveHostInfo(hostInfo)
        
        logger.info("New host created: $hostId at $hostIp")
        return Pair(hostId, hostIp)
    }
    
    /**
     * Waits for a server to become active.
     */
    private suspend fun waitForServerActive(serverId: Long, timeoutMs: Long = 300000): VpsService.ServerInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        
        while (System.currentTimeMillis() < deadline) {
            val info = vpsService.getServer(serverId)
            
            if (info.status == "active" && info.publicIpV4 != null) {
                return info
            }
            
            logger.info("Server $serverId status: ${info.status}, waiting...")
            kotlinx.coroutines.delay(5000)
        }
        
        throw IllegalStateException("Server $serverId did not become active within ${timeoutMs}ms")
    }
    
    /**
     * Generates a random password for the host.
     */
    private fun generatePassword(): String {
        val chars = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!@#$%^&*".toList()).toCharArray()
        return (1..20).map { chars.random() }.joinToString("")
    }
}


