package com.suprbeta.websocket.pipeline

import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*

/**
 * Message pipeline orchestrator implementing chain-of-responsibility pattern
 */
class MessagePipeline(
    private val application: Application,
    private val interceptors: List<MessageInterceptor>
) {
    private val logger = application.log

    /**
     * Process an inbound message (Mobile client → OpenClaw VPS)
     */
    suspend fun processInbound(frame: WebSocketFrame, session: ProxySession): InterceptorResult {
        return processMessage(frame, MessageDirection.INBOUND, session)
    }

    /**
     * Process an outbound message (OpenClaw VPS → Mobile client)
     */
    suspend fun processOutbound(frame: WebSocketFrame, session: ProxySession): InterceptorResult {
        return processMessage(frame, MessageDirection.OUTBOUND, session)
    }

    /**
     * Process a message through all interceptors in the pipeline
     */
    private suspend fun processMessage(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult {
        var currentFrame = frame

        for (interceptor in interceptors) {
            when (val result = interceptor.intercept(currentFrame, direction, session)) {
                is InterceptorResult.Continue -> {
                    currentFrame = result.frame
                }
                is InterceptorResult.Drop -> {
                    logger.debug("Message dropped by ${interceptor::class.simpleName}: ${result.reason}")
                    return result
                }
                is InterceptorResult.Error -> {
                    logger.error("Interceptor error: ${result.message}", result.cause)
                    return result
                }
            }
        }

        return InterceptorResult.Continue(currentFrame)
    }

    /**
     * Add an interceptor to the pipeline dynamically
     */
    fun addInterceptor(interceptor: MessageInterceptor): MessagePipeline {
        return MessagePipeline(application, interceptors + interceptor)
    }
}
