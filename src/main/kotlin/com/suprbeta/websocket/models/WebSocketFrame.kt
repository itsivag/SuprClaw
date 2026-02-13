package com.suprbeta.websocket.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * WebSocket protocol frame matching the mobile client and OpenClaw VPS protocol.
 * All fields are nullable to support various message types.
 */
@Serializable
data class WebSocketFrame(
    val event: String? = null,
    val type: String? = null,
    val id: String? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null
)

/**
 * Full connect request sent in response to connect.challenge
 */
@Serializable
data class ConnectRequest(
    val type: String = "req",
    val id: String = "1",
    val method: String = "connect",
    val params: ConnectParams
)

/**
 * Connection parameters for the connect request
 */
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

/**
 * Client information
 */
@Serializable
data class ClientInfo(
    val id: String,
    val version: String,
    val platform: String,
    val mode: String
)

/**
 * Authentication information
 */
@Serializable
data class AuthInfo(
    val token: String
)
