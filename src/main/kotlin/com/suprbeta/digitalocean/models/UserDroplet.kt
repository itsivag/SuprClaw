package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

/**
 * Represents a user's provisioned droplet in Firestore
 * One user can have only one droplet
 */
@Serializable
data class UserDroplet(
    val userId: String = "",              // Firebase user ID (document ID)
    val dropletId: Long = 0,              // DigitalOcean droplet ID
    val dropletName: String = "",         // Droplet name (usually same as userId)
    val gatewayUrl: String = "",          // Proxy WebSocket URL (wss://api.suprclaw.com/ws)
    val gatewayToken: String = "",        // Authentication token for gateway
    val ipAddress: String = "",           // Droplet IP address
    val subdomain: String? = null,        // Subdomain (if SSL enabled)
    val createdAt: String = "",           // ISO 8601 timestamp
    val status: String = "active",        // Status: active, provisioning, error, deleted
    val sslEnabled: Boolean = true        // Whether SSL/HTTPS is enabled
) {
    // No-arg constructor for Firestore deserialization
    constructor() : this("", 0, "", "", "", "", null, "", "active", true)
}
