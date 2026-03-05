package com.suprbeta.websocket.pipeline

import com.suprbeta.firebase.RemoteConfigService
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RateLimitInterceptor(
    private val application: Application,
    private val remoteConfigService: RemoteConfigService
) : MessageInterceptor {
    
    private val logger = application.log

    override suspend fun intercept(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult {
        // We only rate limit INBOUND chat.send requests
        if (direction != MessageDirection.INBOUND || frame.type != "req" || frame.method != "chat.send") {
            return InterceptorResult.Continue(frame)
        }

        val userTier = session.metadata.userTier
        val limit = remoteConfigService.getDailyCreditLimit(userTier)
        val currentUsage = session.metadata.currentDailyTokens.get()

        if (currentUsage >= limit) {
            val userId = session.metadata.userId
            logger.warn("Rate limit exceeded for user $userId (Tier: $userTier). Usage: $currentUsage, Limit: $limit")

            // Block message and return standard OpenClaw error response to the client
            val errorFrame = WebSocketFrame(
                type = "res",
                id = frame.id,
                ok = false,
                error = buildJsonObject {
                    put("code", "QUOTA_EXCEEDED")
                    put("message", "Daily credit limit exceeded. Please try again tomorrow or upgrade your plan.")
                }
            )

            // Inject the error frame directly back to the client
            try {
                val jsonStr = kotlinx.serialization.json.Json.encodeToString(WebSocketFrame.serializer(), errorFrame)
                session.clientSession?.send(io.ktor.websocket.Frame.Text(jsonStr))
            } catch (e: Exception) {
                logger.error("Failed to send rate limit error frame to client", e)
            }

            return InterceptorResult.Drop("Daily credit limit exceeded")
        }

        return InterceptorResult.Continue(frame)
    }
}
