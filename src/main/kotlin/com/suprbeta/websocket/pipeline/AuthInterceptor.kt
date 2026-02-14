package com.suprbeta.websocket.pipeline

import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interceptor for Firebase authentication and authorization
 *
 * Periodically re-verifies Firebase tokens during long-lived WebSocket sessions
 * to detect token expiration (Firebase ID tokens expire after 1 hour)
 */
class AuthInterceptor(
    private val authService: FirebaseAuthService,
    private val application: Application
) : MessageInterceptor {

    private val messageCounter = AtomicInteger(0)
    private val revalidateInterval = 10 // Re-check token every 10 messages

    override suspend fun intercept(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult {
        val token = session.metadata.clientToken

        // Periodically re-verify token to catch expiration during long-lived sessions
        if (messageCounter.incrementAndGet() % revalidateInterval == 0) {
            val user = authService.verifyToken(token)

            if (user == null) {
                application.log.warn("Token expired for session ${session.sessionId}, user: ${session.metadata.userId}")
                return InterceptorResult.Error("Authentication expired - please reconnect")
            }

            application.log.debug("Token re-validated successfully for session ${session.sessionId}")
        }

        // TODO: Add permission checks based on message type/method
        // Example: Check custom claims for admin/operator roles
        // if (frame.method == "droplet.create" && user.customClaims["role"] != "admin") {
        //     return InterceptorResult.Drop("Insufficient permissions")
        // }

        return InterceptorResult.Continue(frame)
    }
}
