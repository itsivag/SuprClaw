package com.suprbeta.websocket

import com.suprbeta.firebase.FirebaseAuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull

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
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")?.trim()

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
            val userTier = (user.customClaims["tier"] as? String)?.lowercase() ?: "free"
            val userId = user.uid

            logger.info("New WebSocket connection request for user: $userId (Tier: $userTier)")

            // Create or resume proxy session
            val session = sessionManager.getOrCreateSession(
                clientSession = this,
                token = token,
                platform = platform,
                userId = userId,
                userEmail = user.email,
                emailVerified = user.emailVerified,
                authProvider = user.provider,
                userTier = userTier
            )

            var clientCloseReason: CloseReason? = null

            try {
                // Establish runtime connection or direct bridge (noop if already connected)
                val connected = sessionManager.establishRuntimeConnection(session)

                if (!connected) {
                    logger.error("Failed to establish runtime connection for user $userId")
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Failed to connect to tenant runtime"))
                    sessionManager.closeSession(userId)
                    return@webSocket
                }

                // Start bidirectional message forwarding
                sessionManager.startMessageForwarding(session, this)

                logger.info("Session ${session.sessionId} active for user $userId")
                
                // Wait for client to disconnect
                clientCloseReason = closeReason.await()
                logger.info(
                    "Client WebSocket close captured for user $userId: ${describeCloseReason(clientCloseReason)}"
                )

            } catch (e: ClosedReceiveChannelException) {
                clientCloseReason = clientCloseReason ?: awaitCloseReasonSafely(closeReason)
                logger.info(
                    "Client disconnected normally for user $userId (${describeCloseReason(clientCloseReason)})"
                )
            } catch (e: Exception) {
                clientCloseReason = clientCloseReason ?: awaitCloseReasonSafely(closeReason)
                logger.error(
                    "Error during WebSocket session for user $userId: ${e.message} " +
                        "(${describeCloseReason(clientCloseReason)})"
                )
            } finally {
                // Instead of closing the session, we trigger the offline grace period
                val closeSummary = describeCloseReason(clientCloseReason)
                logger.info("WebSocket connection closed for user $userId ($closeSummary). Triggering offline grace period.")
                sessionManager.handleClientDisconnect(userId, this)
            }
        }

        // Health check endpoint
        get("/ws/health") {
            call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
        }
    }
}

private fun describeCloseReason(reason: CloseReason?): String {
    if (reason == null) return "close=unavailable"

    val message = reason.message.takeIf { it.isNotBlank() } ?: "<empty>"
    return "close.code=${reason.code} close.message=$message"
}

private suspend fun awaitCloseReasonSafely(reasonDeferred: Deferred<CloseReason?>): CloseReason? {
    return runCatching { withTimeoutOrNull(250) { reasonDeferred.await() } }.getOrNull()
}
