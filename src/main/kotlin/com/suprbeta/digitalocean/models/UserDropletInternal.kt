package com.suprbeta.digitalocean.models

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
    val gatewayToken: String = "",        // Authentication token for gateway
    val sshKey: String = "",              // SSH credential used for openclaw user on the VPS
    val ipAddress: String = "",           // Droplet IP address
    val subdomain: String? = null,        // Subdomain (if SSL enabled)
    val createdAt: String = "",           // ISO 8601 timestamp
    val status: String = "active",        // Status: active, provisioning, error, deleted
    val sslEnabled: Boolean = true,       // Whether SSL/HTTPS is enabled
    val supabaseProjectRef: String = "",  // User's Supabase project ref (e.g. "abcxyz123")
    val supabaseServiceKey: String = "",  // User's Supabase service role key
    val configuredMcpTools: List<String> = listOf("supabase") // MCP tools configured on this VPS
) {
    // No-arg constructor for Firestore deserialization
    constructor() : this("", 0, "", "", "", "", "", "", null, "", "active", true, "", "", listOf("supabase"))
    
    /**
     * Convert to client-safe UserDroplet (without vpsGatewayUrl)
     * This method strips sensitive VPS URL before sending to clients
     */
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
