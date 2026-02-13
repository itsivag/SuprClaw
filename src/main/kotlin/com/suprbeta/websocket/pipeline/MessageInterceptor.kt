package com.suprbeta.websocket.pipeline

import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame

/**
 * Direction of message flow through the proxy
 */
enum class MessageDirection {
    INBOUND,  // Mobile client → OpenClaw VPS
    OUTBOUND  // OpenClaw VPS → Mobile client
}

/**
 * Result of message interception
 */
sealed class InterceptorResult {
    /**
     * Continue processing the message through the pipeline
     */
    data class Continue(val frame: WebSocketFrame) : InterceptorResult()

    /**
     * Drop the message (do not forward)
     */
    data class Drop(val reason: String) : InterceptorResult()

    /**
     * Error occurred during interception
     */
    data class Error(val message: String, val cause: Throwable? = null) : InterceptorResult()
}

/**
 * Interface for message interceptors in the proxy pipeline
 */
interface MessageInterceptor {
    /**
     * Intercept a message passing through the proxy
     *
     * @param frame The WebSocket frame to intercept
     * @param direction Direction of message flow
     * @param session The proxy session this message belongs to
     * @return Result indicating whether to continue, drop, or error
     */
    suspend fun intercept(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult
}
