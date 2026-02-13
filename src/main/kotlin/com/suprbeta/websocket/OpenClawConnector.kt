package com.suprbeta.websocket

import com.suprbeta.config.WebSocketConfig
import com.suprbeta.websocket.models.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
     * @return WebSocketSession if successful, null if all retries failed
     */
    suspend fun connect(token: String): DefaultWebSocketSession? {
        val maxRetries = 3
        val retryDelay = 1000L // 1 second

        repeat(maxRetries) { attempt ->
            try {
                logger.info("Connecting to OpenClaw VPS (attempt ${attempt + 1}/$maxRetries)...")

                val session = httpClient.webSocketSession(
                    urlString = "${WebSocketConfig.OPENCLAW_WS_URL}?token=$token"
                )

                logger.info("Connected to OpenClaw VPS successfully")
                return session

            } catch (e: Exception) {
                logger.error("Failed to connect to OpenClaw VPS (attempt ${attempt + 1}/$maxRetries): ${e.message}")

                if (attempt < maxRetries - 1) {
                    delay(retryDelay)
                }
            }
        }

        logger.error("Failed to connect to OpenClaw VPS after $maxRetries attempts")
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
            val connectRequest = ConnectRequest(
                type = "req",
                id = "1",
                method = "connect",
                params = ConnectParams(
                    minProtocol = 3,
                    maxProtocol = 3,
                    client = ClientInfo(
                        id = "suprclaw-proxy",
                        version = "1.0.0",
                        platform = platform,
                        mode = "proxy"
                    ),
                    role = "operator",
                    scopes = listOf(
                        "operator.read",
                        "operator.write",
                        "operator.admin",
                        "operator.approvals"
                    ),
                    caps = emptyList(),
                    commands = emptyList(),
                    permissions = emptyMap(),
                    auth = AuthInfo(token = token),
                    locale = "en-US",
                    userAgent = "suprclaw-proxy/1.0.0"
                )
            )

            val requestJson = json.encodeToString(connectRequest)
            session.send(Frame.Text(requestJson))

            logger.info("Sent ConnectRequest to OpenClaw VPS (auto-handled connect.challenge)")

        } catch (e: Exception) {
            logger.error("Failed to handle connect.challenge: ${e.message}", e)
            throw e
        }
    }
}
