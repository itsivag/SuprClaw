package com.suprbeta.websocket.pipeline

import com.suprbeta.chat.ChatHistoryService
import com.suprbeta.firebase.RemoteConfigService
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RateLimitInterceptor(
    private val application: Application,
    private val remoteConfigService: RemoteConfigService,
    private val chatHistoryService: ChatHistoryService
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
        val limit = remoteConfigService.getWeeklyCreditLimit(userTier)
        val currentUsage = session.metadata.currentWeeklyCredits.get()

        if (currentUsage >= limit) {
            val userId = session.metadata.userId
            logger.warn("Rate limit exceeded for user $userId (Tier: $userTier). Usage: $currentUsage credits, Limit: $limit credits")

            // Block the message and return the standard quota response to the client.
            val errorFrame = WebSocketFrame(
                type = "res",
                id = frame.id,
                ok = false,
                error = buildJsonObject {
                    put("code", "QUOTA_EXCEEDED")
                    put("message", "Weekly credit limit exceeded ($limit credits/week). Please try again next week or upgrade your plan.")
                }
            )

            // Inject the error frame directly back to the client
            try {
                val jsonStr = kotlinx.serialization.json.Json.encodeToString(WebSocketFrame.serializer(), errorFrame)
                session.clientSession?.send(io.ktor.websocket.Frame.Text(jsonStr))
                runCatching {
                    chatHistoryService.captureSystemError(
                        session = session,
                        sourceFrame = frame,
                        code = "QUOTA_EXCEEDED",
                        message = "Weekly credit limit exceeded ($limit credits/week). Please try again next week or upgrade your plan."
                    )
                }.onFailure {
                    logger.error("Failed to capture rate limit chat history entry", it)
                }
            } catch (e: Exception) {
                logger.error("Failed to send rate limit error frame to client", e)
            }

            return InterceptorResult.Drop("Weekly credit limit exceeded")
        }

        return InterceptorResult.Continue(frame)
    }
}
