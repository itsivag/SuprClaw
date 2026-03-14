package com.suprbeta.podman

import com.suprbeta.config.AppConfig
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.UserDataGenerator
import com.suprbeta.podman.models.HostInfo
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.provider.VpsService
import io.github.cdimascio.dotenv.dotenv
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
    private val sshCommandExecutor: SshCommandExecutor,
    private val application: Application
) {
    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    companion object {
        private const val HOST_NAME_PREFIX = "suprclaw-host"
        private const val SENTINEL_PATH = "/var/run/suprclaw-host-ready"
        private const val HOST_READY_TIMEOUT_MS = 600_000L  // 10 min
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
        firestoreRepository.incrementHostContainerCount(hostId, AppConfig.podmanHostCapacity)
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

        // Only schedule onto hosts that still accept the provisioning SSH key.
        val candidates = hosts
            .filter { it.status == HostInfo.STATUS_ACTIVE && !it.isFull }
            .sortedByDescending { it.availableCapacity }

        for (host in candidates) {
            if (isHostProvisioningReady(host)) {
                return host
            }
            markHostUnhealthy(host)
        }

        return null
    }

    private fun isHostProvisioningReady(host: HostInfo): Boolean {
        return try {
            val output = sshCommandExecutor.runSshCommand(
                host.hostIp,
                "id -u picoclaw >/dev/null 2>&1 && command -v podman >/dev/null 2>&1 && test -f $SENTINEL_PATH && echo READY || echo NOT_READY"
            ).trim()
            output == "READY"
        } catch (e: Exception) {
            logger.warn("Skipping host ${host.hostId} at ${host.hostIp}: ${e.message}")
            false
        }
    }

    private suspend fun markHostUnhealthy(host: HostInfo) {
        val updatedHost = host.copy(status = HostInfo.STATUS_ERROR)
        firestoreRepository.saveHostInfo(updatedHost)
        logger.warn("Marked host ${host.hostId} at ${host.hostIp} as ${HostInfo.STATUS_ERROR}")
    }
    
    /**
     * Creates a new host VPS with Podman + Traefik pre-installed via cloud-init.
     */
    private suspend fun createNewHost(): Pair<Long, String> {
        val hostName = "$HOST_NAME_PREFIX-${System.currentTimeMillis()}"

        val sshPublicKey = dotenv["PROVISIONING_SSH_PUBLIC_KEY"]
            ?: System.getenv("PROVISIONING_SSH_PUBLIC_KEY") ?: ""
        if (sshPublicKey.isBlank()) {
            logger.warn("PROVISIONING_SSH_PUBLIC_KEY not set — runtime SSH key auth will not work on new host")
        }

        logger.info("Creating new Podman host VPS: $hostName")

        val userData = UserDataGenerator.generatePodmanHostUserData(sshPublicKey)
        val result = vpsService.createServer(hostName, password = "", userDataOverride = userData)
        val hostId = result.serverId

        // Wait for Hetzner to report server as running
        val serverInfo = waitForServerActive(hostId)
        val hostIp = serverInfo.publicIpV4
            ?: throw IllegalStateException("Server $hostId has no public IP")

        // Wait for cloud-init to finish (Podman + Traefik install takes several minutes)
        logger.info("Server $hostId active at $hostIp — waiting for Podman host setup to complete...")
        waitForPodmanHostReady(hostIp)

        // Save host info to Firestore
        val hostInfo = HostInfo(
            hostId = hostId,
            hostIp = hostIp,
            totalCapacity = AppConfig.podmanHostCapacity,
            currentContainers = 0,
            status = HostInfo.STATUS_ACTIVE,
            createdAt = Instant.now().toString(),
            region = "nbg1"
        )
        firestoreRepository.saveHostInfo(hostInfo)

        logger.info("New Podman host ready: $hostId at $hostIp")
        return Pair(hostId, hostIp)
    }

    /**
     * Polls via SSH until cloud-init writes the sentinel file, meaning Podman + Traefik are ready.
     */
    private suspend fun waitForPodmanHostReady(hostIp: String) {
        val deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MS
        var lastError = ""

        while (System.currentTimeMillis() < deadline) {
            try {
                val output = sshCommandExecutor.runSshCommand(
                    hostIp,
                    "test -f $SENTINEL_PATH && echo READY || echo WAITING"
                )
                if (output.trim() == "READY") {
                    logger.info("Podman host at $hostIp is ready")
                    return
                }
            } catch (e: Exception) {
                lastError = e.message ?: "unknown"
                logger.debug("Podman host $hostIp not ready yet: $lastError")
            }
            kotlinx.coroutines.delay(10_000)
        }

        throw IllegalStateException(
            "Podman host $hostIp did not become ready within ${HOST_READY_TIMEOUT_MS / 1000}s (last error: $lastError)"
        )
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
