package com.suprbeta.digitalocean.models

import com.google.cloud.firestore.annotation.Exclude
import com.suprbeta.runtime.AgentRuntime
import kotlinx.serialization.Serializable

/**
 * Internal representation of user droplet with VPS gateway URL
 * Used only by backend services - NOT exposed to clients
 *
 * This class contains sensitive information (vpsGatewayUrl, sshKey) that should
 * only be used by the proxy to route connections to the user's specific VPS.
 */
@Serializable
data class UserDropletInternal(
    val userId: String = "",              // Firebase user ID (document ID)
    val dropletId: Long = 0,              // DigitalOcean droplet ID
    val dropletName: String = "",         // Droplet name (usually same as userId)
    val gatewayUrl: String = "",          // Proxy WebSocket URL for clients (wss://api.suprclaw.com/ws)
    val vpsGatewayUrl: String = "",       // Actual VPS gateway URL (backend only, e.g., https://subdomain.suprclaw.com)
    val gatewayToken: String = "",        // Authentication token for runtime access
    val sshKey: String = "",              // SSH credential used for runtime user on the VPS
    val ipAddress: String = "",           // Droplet IP address
    val subdomain: String? = null,        // Subdomain (if SSL enabled)
    val createdAt: String = "",           // ISO 8601 timestamp
    val status: String = "active",        // Status: active, provisioning, error, deleted
    val sslEnabled: Boolean = true,       // Whether SSL/HTTPS is enabled
    val supabaseProjectRef: String = "",  // User's Supabase project ref (hosted: project ID; self-hosted: schema name)
    val supabaseServiceKey: String = "",  // User's Supabase service role key
    val supabaseUrl: String = "",         // Full Supabase base URL (empty = derive from supabaseProjectRef for hosted)
    val supabaseSchema: String = "public", // PostgREST schema to query (hosted: "public"; self-hosted: schema name)
    val configuredMcpTools: List<String> = emptyList(), // Transitional native MCP servers configured on this runtime
    val deploymentMode: String = "",      // "vps" or "podman"
    val agentRuntime: String = AgentRuntime.PICOCLAW.wireValue
) {
    companion object {
        private val CONTAINER_ID_REGEX = Regex("^[a-f0-9]{12,64}$")
    }

    // No-arg constructor for Firestore deserialization
    constructor() : this(
        "", 0, "", "", "", "", "", "", null, "", "active", true,
        "", "", "", "public", emptyList(), "", AgentRuntime.PICOCLAW.wireValue
    )

    /**
     * Resolves the full Supabase base URL.
     * For hosted: derives URL from supabaseProjectRef if supabaseUrl is blank.
     * For self-hosted: uses the stored supabaseUrl directly.
     */
    @Exclude
    fun resolveSupabaseUrl(): String = supabaseUrl.ifBlank { "https://$supabaseProjectRef.supabase.co" }

    @Exclude
    fun isContainerDeployment(): Boolean =
        deploymentMode.equals("podman", ignoreCase = true) ||
            (deploymentMode.isBlank() && CONTAINER_ID_REGEX.matches(dropletName.trim()))

    @Exclude
    fun containerIdOrNull(): String? {
        val candidate = dropletName.trim()
        return candidate.takeIf { isContainerDeployment() && CONTAINER_ID_REGEX.matches(it) }
    }

    @Exclude
    fun resolvedAgentRuntime(): AgentRuntime = AgentRuntime.fromWireValue(agentRuntime)

    @Exclude
    fun isPicoClawRuntime(): Boolean = resolvedAgentRuntime() == AgentRuntime.PICOCLAW

    // Firestore legacy compatibility for older documents that stored computed flags.
    var picoClawRuntime: Boolean = false
        get() = isPicoClawRuntime()
        set(value) {
            field = value
        }

    /**
     * Convert to client-safe UserDroplet (without vpsGatewayUrl)
     * This method strips sensitive VPS URL before sending to clients
     */
    @Exclude
    fun toUserDroplet() = UserDroplet(
        userId = userId,
        dropletId = dropletId,
        dropletName = dropletName,
        gatewayUrl = gatewayUrl,
        gatewayToken = gatewayToken,
        createdAt = createdAt,
        status = status
    )
}
