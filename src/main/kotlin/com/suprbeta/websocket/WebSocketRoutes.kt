package com.suprbeta.websocket

import com.suprbeta.firebase.FirebaseAuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

/**
 * Configure WebSocket routes for the proxy
 */
fun Application.configureWebSocketRoutes(
    sessionManager: ProxySessionManager,
    authService: FirebaseAuthService
) {
    val logger = log

    routing {
        // WebSocket endpoint for mobile clients
        webSocket("/ws") {
            val token = call.request.queryParameters["token"]

            if (token.isNullOrBlank()) {
                logger.warn("WebSocket connection rejected: missing token")
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing token parameter"))
                return@webSocket
            }

            // Verify Firebase token
            val user = authService.verifyToken(token)
            if (user == null) {
                logger.warn("WebSocket connection rejected: invalid or expired token")
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid or expired token"))
                return@webSocket
            }

            val platform = call.request.queryParameters["platform"]

            logger.info("New WebSocket connection request for user: ${user.uid}")

            // Create proxy session with verified user information
            val session = sessionManager.createSession(
                clientSession = this,
                token = token,
                platform = platform,
                userId = user.uid,
                userEmail = user.email,
                emailVerified = user.emailVerified,
                authProvider = user.provider
            )

            try {
                // Establish connection to OpenClaw VPS
                val connected = sessionManager.establishOpenClawConnection(session)

                if (!connected) {
                    logger.error("Failed to establish OpenClaw connection for session ${session.sessionId}")
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Failed to connect to OpenClaw VPS"))
                    sessionManager.closeSession(session.sessionId)
                    return@webSocket
                }

                // Start bidirectional message forwarding
                sessionManager.startMessageForwarding(session, this)

                logger.info("Session ${session.sessionId} fully initialized and forwarding messages")

            } catch (e: Exception) {
                logger.error("Error during WebSocket session ${session.sessionId}: ${e.message}", e)
                sessionManager.closeSession(session.sessionId)
                throw e
            } finally {
                // Cleanup when connection closes
                logger.info("WebSocket connection closed for session ${session.sessionId}")
                sessionManager.closeSession(session.sessionId)
            }
        }

        // Health check endpoint
        get("/ws/health") {
            val activeSessionCount = sessionManager.getSessionCount()
            val activeSessions = sessionManager.getActiveSessions()

            val healthInfo = buildString {
                appendLine("WebSocket Proxy Health")
                appendLine("Active Sessions: $activeSessionCount")
                appendLine()

                if (activeSessions.isNotEmpty()) {
                    appendLine("Session Details:")
                    activeSessions.forEach { (sessionId, session) ->
                        appendLine("  - $sessionId:")
                        appendLine("    User ID: ${session.metadata.userId}")
                        appendLine("    Email: ${session.metadata.userEmail ?: "none"}")
                        appendLine("    Email Verified: ${session.metadata.emailVerified}")
                        appendLine("    Auth Provider: ${session.metadata.authProvider ?: "unknown"}")
                        appendLine("    Platform: ${session.metadata.platform ?: "unknown"}")
                        appendLine("    Connected: ${session.metadata.connectedAt}")
                        appendLine("    Messages Sent: ${session.metadata.getSentCount()}")
                        appendLine("    Messages Received: ${session.metadata.getReceivedCount()}")
                        appendLine("    OpenClaw Connected: ${session.isOpenClawConnected}")
                        appendLine("    Forwarding Active: ${session.hasActiveForwarding()}")
                    }
                }
            }

            call.respondText(healthInfo, ContentType.Text.Plain, HttpStatusCode.OK)
        }
    }
}
