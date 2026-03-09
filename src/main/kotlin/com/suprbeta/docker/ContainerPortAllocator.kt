package com.suprbeta.docker

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages port allocation for containers across host VPS instances.
 * 
 * Each host gets a dedicated range of ports (default: 18001-18050).
 * This service tracks which ports are in use per host and allocates
 * available ports for new containers.
 */
class ContainerPortAllocator(
    private val startPort: Int = 18001,
    private val endPort: Int = 18050
) {
    
    // Map of hostId to set of allocated ports
    private val allocatedPorts = ConcurrentHashMap<Long, MutableSet<Int>>()
    
    /**
     * Allocates an available port for a new container on the specified host.
     * 
     * @param hostId The host VPS ID
     * @return An available port number
     * @throws IllegalStateException if no ports are available
     */
    fun allocatePort(hostId: Long): Int {
        val ports = allocatedPorts.computeIfAbsent(hostId) { ConcurrentHashMap.newKeySet() }
        
        synchronized(ports) {
            // Find first available port in range
            for (port in startPort..endPort) {
                if (!ports.contains(port)) {
                    ports.add(port)
                    return port
                }
            }
        }
        
        throw IllegalStateException(
            "No available ports for host $hostId (range: $startPort-$endPort, " +
            "used: ${ports.size})"
        )
    }
    
    /**
     * Releases a port back to the available pool.
     * 
     * @param hostId The host VPS ID
     * @param port The port to release
     */
    fun releasePort(hostId: Long, port: Int) {
        allocatedPorts[hostId]?.remove(port)
    }
    
    /**
     * Checks if a specific port is available on a host.
     * 
     * @param hostId The host VPS ID
     * @param port The port to check
     * @return true if the port is available
     */
    fun isPortAvailable(hostId: Long, port: Int): Boolean {
        return !(allocatedPorts[hostId]?.contains(port) ?: false)
    }
    
    /**
     * Gets the number of allocated ports for a host.
     * 
     * @param hostId The host VPS ID
     * @return Number of allocated ports
     */
    fun getAllocatedPortCount(hostId: Long): Int {
        return allocatedPorts[hostId]?.size ?: 0
    }
    
    /**
     * Gets the list of allocated ports for a host.
     * 
     * @param hostId The host VPS ID
     * @return Set of allocated ports
     */
    fun getAllocatedPorts(hostId: Long): Set<Int> {
        return allocatedPorts[hostId]?.toSet() ?: emptySet()
    }
    
    /**
     * Gets the total capacity (number of available ports per host).
     */
    fun getCapacity(): Int = endPort - startPort + 1
    
    /**
     * Gets the number of available ports for a host.
     * 
     * @param hostId The host VPS ID
     * @return Number of available ports
     */
    fun getAvailablePortCount(hostId: Long): Int {
        return getCapacity() - getAllocatedPortCount(hostId)
    }
    
    /**
     * Clears all port allocations (useful for testing).
     */
    fun clearAll() {
        allocatedPorts.clear()
    }
    
    /**
     * Pre-allocates a set of ports for a host (useful for recovery/restart scenarios).
     * 
     * @param hostId The host VPS ID
     * @param ports Set of ports that are already in use
     */
    fun preallocatePorts(hostId: Long, ports: Set<Int>) {
        val hostPorts = allocatedPorts.computeIfAbsent(hostId) { ConcurrentHashMap.newKeySet() }
        hostPorts.addAll(ports.filter { it in startPort..endPort })
    }
}
