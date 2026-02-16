package com.suprbeta.websocket.pipeline

import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*

/**
 * Interceptor that logs all messages passing through the proxy
 */
class LoggingInterceptor(
    private val application: Application
) : MessageInterceptor {
    private val logger = application.log

    override suspend fun intercept(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult {
        val directionSymbol = when (direction) {
            MessageDirection.INBOUND -> "→"
            MessageDirection.OUTBOUND -> "←"
        }

        val logMessage = buildString {
            append("$directionSymbol [${session.sessionId.take(8)}] ")

            frame.event?.let { append("event=$it ") }
            frame.type?.let { append("type=$it ") }
            frame.method?.let { append("method=$it ") }

            if (frame.event == "connect.challenge") {
                append("(auto-handled)")
            }
        }

        logger.debug(logMessage)

        // Log session lifecycle events at INFO level
        if (frame.event in listOf("connect", "disconnect", "connect.challenge")) {
            logger.info(logMessage)
        }

        // Keep request/response flow visible in production logs during proxy bring-up.
        if (frame.method == "chat.send" || frame.method == "send") {
            logger.info(logMessage)
        }
        if (frame.type == "res" && frame.id != null) {
            logger.info(logMessage)
        }

        return InterceptorResult.Continue(frame)
    }
}
