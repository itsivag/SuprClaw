package com.suprbeta.podman.models

import com.google.cloud.firestore.annotation.Exclude
import kotlinx.serialization.Serializable

/**
 * Information about a running Podman container for a user.
 */
@Serializable
data class ContainerInfo(
    val containerId: String,
    val userId: String,
    val port: Int,
    val subdomain: String,
    val gatewayToken: String,
    val supabaseProjectRef: String,
    val createdAt: String,
    val status: String,
    val agentRuntime: String = "picoclaw"
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val STATUS_CREATING = "creating"
    }
}

/**
 * Configuration for Supabase connection within a container.
 */
@Serializable
data class SupabaseConfig(
    val url: String,
    val serviceKey: String,
    val projectRef: String,
    val schema: String = "public",
    val apiKey: String = ""
)

/**
 * Request to create a new container.
 */
data class ContainerCreateRequest(
    val userId: String,
    val gatewayToken: String,
    val supabaseConfig: SupabaseConfig,
    val mcpTools: List<McpToolConfig>,
    val hostPort: Int
)

/**
 * MCP tool configuration.
 */
@Serializable
data class McpToolConfig(
    val name: String,
    val envVars: Map<String, String> = emptyMap()
)

/**
 * Host VPS information tracked by HostPoolManager.
 */
@Serializable
data class HostInfo(
    val hostId: Long = 0,
    val hostIp: String = "",
    val totalCapacity: Int = 20,
    val currentContainers: Int = 0,
    val status: String = STATUS_ACTIVE,
    val createdAt: String = "",
    val region: String = ""
) {
    // No-arg constructor required for Firestore POJO deserialization
    constructor() : this(0, "", 20, 0, STATUS_ACTIVE, "", "")

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_FULL = "full"
        const val STATUS_MAINTENANCE = "maintenance"
        const val STATUS_ERROR = "error"
    }

    // Firestore legacy compatibility: older documents may contain this field.
    var availableCapacity: Int = 0
        get() = totalCapacity - currentContainers
        set(value) {
            field = value
        }

    // Firestore maps Kotlin `isFull` to the bean property name `full`.
    var full: Boolean = false
        get() = currentContainers >= totalCapacity
        set(value) {
            field = value
        }

    @get:Exclude
    val isFull: Boolean
        get() = full
}

/**
 * Traefik router configuration for a user container.
 */
@Serializable
data class TraefikRouterConfig(
    val subdomain: String,
    val hostPort: Int,
    val tls: Boolean = true
)

/**
 * Represents a user's container-based "droplet" in the multi-tenant architecture.
 * This is stored in Firestore and maps to the existing UserDropletInternal structure.
 */
@Serializable
data class UserHostDroplet(
    val userId: String,
    val hostServerId: Long,
    val hostIp: String,
    val containerId: String,
    val port: Int,
    val subdomain: String,
    val gatewayUrl: String,
    val vpsGatewayUrl: String,
    val gatewayToken: String,
    val supabaseProjectRef: String,
    val supabaseServiceKey: String,
    val supabaseUrl: String,
    val supabaseSchema: String = "public",
    val createdAt: String,
    val status: String = "active",
    val configuredMcpTools: List<String> = emptyList(),
    val agentRuntime: String = "picoclaw"
) {
    /**
     * Convert to client-safe UserDroplet (without sensitive/internal fields).
     */
    fun toUserDroplet(): com.suprbeta.digitalocean.models.UserDroplet {
        return com.suprbeta.digitalocean.models.UserDroplet(
            userId = userId,
            dropletId = hostServerId,
            dropletName = containerId.take(12),
            gatewayUrl = gatewayUrl,
            gatewayToken = gatewayToken,
            createdAt = createdAt,
            status = status
        )
    }
}
