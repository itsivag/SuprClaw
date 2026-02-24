package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

/**
 * Client-safe representation of a user's provisioned droplet.
 * Does NOT contain sensitive infrastructure details (IP, VPS URL, SSH key, subdomain).
 */
@Serializable
data class UserDroplet(
    val userId: String = "",              // Firebase user ID (document ID)
    val dropletId: Long = 0,              // DigitalOcean droplet ID (needed for status polling)
    val dropletName: String = "",         // Droplet name
    val gatewayUrl: String = "",          // Proxy WebSocket URL (wss://api.suprclaw.com)
    val gatewayToken: String = "",        // Authentication token for gateway
    val createdAt: String = "",           // ISO 8601 timestamp
    val status: String = "active"         // Status: active, provisioning, error, deleted
) {
    // No-arg constructor for Firestore deserialization
    constructor() : this("", 0, "", "", "", "", "active")
}
