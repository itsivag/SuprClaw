package com.suprbeta.websocket.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * App-facing websocket frame used by the SuprClaw mobile/backend bridge.
 */
@Serializable
data class WebSocketFrame(
    val type: String? = null,    // "req", "res", "event", "hello-ok"
    val id: String? = null,      // Request/Response correlation ID
    val method: String? = null,  // Method name for requests
    val event: String? = null,   // Event name for events (e.g., "chat", "tick")
    val ok: Boolean? = null,     // Success flag for responses
    val seq: Int? = null,        // Sequence number for events
    val params: JsonElement? = null,  // Request parameters
    val payload: JsonElement? = null, // Response/Event payload
    val result: JsonElement? = null,  // Legacy/Alternate response payload
    val error: JsonElement? = null,   // Error object
    val state: String? = null    // State for streaming (delta, final, etc.)
)

@Serializable
data class ConnectRequest(
    val type: String = "req",
    val id: String = "1",
    val method: String = "connect",
    val params: ConnectParams
)

@Serializable
data class ConnectParams(
    val minProtocol: Int = 3,
    val maxProtocol: Int = 3,
    val client: ClientInfo,
    val role: String,
    val scopes: List<String>,
    val caps: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val permissions: Map<String, String> = emptyMap(),
    val auth: AuthInfo,
    val locale: String = "en-US",
    val userAgent: String = "suprclaw-proxy/1.0"
)

@Serializable
data class ClientInfo(
    val id: String,
    val version: String,
    val platform: String,
    val mode: String
)

@Serializable
data class AuthInfo(
    val token: String
)
