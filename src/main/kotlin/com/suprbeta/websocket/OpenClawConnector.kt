package com.suprbeta.websocket

import com.suprbeta.websocket.models.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Handles connections to the OpenClaw VPS WebSocket server
 */
class OpenClawConnector(
    private val application: Application,
    private val httpClient: HttpClient,
    private val json: Json
) {
    private val logger = application.log

    /**
     * Connect to OpenClaw VPS with retry logic
     *
     * @param token Authentication token
     * @param vpsGatewayUrl The VPS gateway URL to connect to (e.g., https://subdomain.suprclaw.com)
     * @return WebSocketSession if successful, null if all retries failed
     */
    suspend fun connect(token: String, vpsGatewayUrl: String): DefaultWebSocketSession? {
        val maxRetries = 3
        val retryDelay = 1000L // 1 second

        repeat(maxRetries) { attempt ->
            try {
                logger.info("Connecting to OpenClaw VPS at $vpsGatewayUrl (attempt ${attempt + 1}/$maxRetries)...")
                val wsUrl = vpsGatewayUrl
                    .replace("https://", "wss://")

                val session = httpClient.webSocketSession(
                    urlString = "$wsUrl/ws?token=$token"
                )

                logger.info("Connected to OpenClaw VPS successfully at $vpsGatewayUrl")
                return session

            } catch (e: Exception) {
                logger.error("Failed to connect to OpenClaw VPS at $vpsGatewayUrl (attempt ${attempt + 1}/$maxRetries): ${e.message}")

                if (attempt < maxRetries - 1) {
                    delay(retryDelay)
                }
            }
        }

        logger.error("Failed to connect to OpenClaw VPS at $vpsGatewayUrl after $maxRetries attempts")
        return null
    }

    /**
     * Handle connect.challenge event from OpenClaw VPS
     * This method automatically responds with a full ConnectRequest
     *
     * @param session OpenClaw WebSocket session
     * @param token Authentication token
     * @param platform Platform identifier (android, ios, etc.)
     */
    suspend fun handleConnectChallenge(
        session: DefaultWebSocketSession,
        token: String,
        platform: String = "proxy"
    ) {
        try {
            // Build a strict request frame to match OpenClaw protocol expectations exactly.
            val requestJson = buildJsonObject {
                put("type", "req")
                put("id", "1")
                put("method", "connect")
                putJsonObject("params") {
                    put("minProtocol", 3)
                    put("maxProtocol", 3)
                    putJsonObject("client") {
                        put("id", "cli")
                        put("version", "2026.2.9")
                        put("platform", platform)
                        put("mode", "node")
                    }
                    put("role", "operator")
                    put(
                        "scopes",
                        buildJsonArray {
                            add(JsonPrimitive("operator.read"))
                            add(JsonPrimitive("operator.write"))
                            add(JsonPrimitive("operator.admin"))
                            add(JsonPrimitive("operator.approvals"))
                        }
                    )
                    put("caps", buildJsonArray { })
                    put("commands", buildJsonArray { })
                    putJsonObject("permissions") { }
                    putJsonObject("auth") {
                        put("token", token)
                    }
                    put("locale", "en-US")
                    put("userAgent", "openclaw-kmp/2026.2.9")
                }
            }.toString()
            session.send(Frame.Text(requestJson))

            logger.info("Sent ConnectRequest to OpenClaw VPS (auto-handled connect.challenge)")

        } catch (e: Exception) {
            logger.error("Failed to handle connect.challenge: ${e.message}", e)
            throw e
        }
    }
}
