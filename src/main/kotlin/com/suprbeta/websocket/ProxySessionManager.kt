package com.suprbeta.websocket

import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.SessionMetadata
import com.suprbeta.websocket.models.WebSocketFrame
import com.suprbeta.websocket.pipeline.InterceptorResult
import com.suprbeta.websocket.pipeline.MessagePipeline
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages proxy sessions between mobile clients and OpenClaw VPS
 */
class ProxySessionManager(
    private val application: Application,
    private val openClawConnector: OpenClawConnector,
    private val messagePipeline: MessagePipeline,
    private val json: Json
) {
    private val logger = application.log
    private val sessions = ConcurrentHashMap<String, ProxySession>()

    /**
     * Create a new proxy session for a mobile client
     *
     * @param clientSession The mobile client's WebSocket session
     * @param token Authentication token
     * @param platform Optional platform identifier
     * @return The created ProxySession
     */
    fun createSession(
        clientSession: DefaultWebSocketSession,
        token: String,
        platform: String? = null
    ): ProxySession {
        val metadata = SessionMetadata(
            clientToken = token,
            platform = platform
        )

        val session = ProxySession(
            clientSession = clientSession,
            metadata = metadata
        )

        sessions[session.sessionId] = session
        logger.info("Created proxy session ${session.sessionId} for platform: ${platform ?: "unknown"}")

        return session
    }

    /**
     * Establish connection to OpenClaw VPS for a proxy session
     *
     * @param session The proxy session
     * @return true if connection successful, false otherwise
     */
    suspend fun establishOpenClawConnection(session: ProxySession): Boolean {
        try {
            val openClawSession = openClawConnector.connect(session.metadata.clientToken)

            if (openClawSession == null) {
                logger.error("Failed to connect to OpenClaw VPS for session ${session.sessionId}")
                return false
            }

            session.openclawSession = openClawSession
            logger.info("Established OpenClaw connection for session ${session.sessionId}")

            return true

        } catch (e: Exception) {
            logger.error("Error establishing OpenClaw connection for session ${session.sessionId}: ${e.message}", e)
            return false
        }
    }

    /**
     * Start bidirectional message forwarding between client and OpenClaw
     *
     * @param session The proxy session
     * @param scope Coroutine scope for launching forwarding jobs
     */
    fun startMessageForwarding(session: ProxySession, scope: CoroutineScope) {
        if (!session.isOpenClawConnected) {
            logger.error("Cannot start message forwarding: OpenClaw not connected for session ${session.sessionId}")
            return
        }

        val openClawSession = session.openclawSession!!

        // Inbound job: Mobile client → OpenClaw VPS
        val inboundJob = scope.launch {
            try {
                for (frame in session.clientSession.incoming) {
                    if (frame is Frame.Text) {
                        handleInboundMessage(session, frame.readText(), openClawSession)
                    }
                }
            } catch (e: Exception) {
                logger.error("Inbound forwarding error for session ${session.sessionId}: ${e.message}", e)
            } finally {
                logger.info("Inbound forwarding stopped for session ${session.sessionId}")
            }
        }

        // Outbound job: OpenClaw VPS → Mobile client
        val outboundJob = scope.launch {
            try {
                for (frame in openClawSession.incoming) {
                    if (frame is Frame.Text) {
                        handleOutboundMessage(session, frame.readText())
                    }
                }
            } catch (e: Exception) {
                logger.error("Outbound forwarding error for session ${session.sessionId}: ${e.message}", e)
            } finally {
                logger.info("Outbound forwarding stopped for session ${session.sessionId}")
            }
        }

        session.forwardingJobs = Pair(inboundJob, outboundJob)
        logger.info("Started message forwarding for session ${session.sessionId}")
    }

    /**
     * Handle an inbound message from mobile client
     */
    private suspend fun handleInboundMessage(
        session: ProxySession,
        messageText: String,
        openClawSession: DefaultWebSocketSession
    ) {
        try {
            val frame = json.decodeFromString<WebSocketFrame>(messageText)
            session.metadata.incrementReceived()

            when (val result = messagePipeline.processInbound(frame, session)) {
                is InterceptorResult.Continue -> {
                    val processedJson = json.encodeToString(result.frame)
                    openClawSession.send(Frame.Text(processedJson))
                    session.metadata.incrementSent()
                }
                is InterceptorResult.Drop -> {
                    logger.debug("Dropped inbound message for session ${session.sessionId}: ${result.reason}")
                }
                is InterceptorResult.Error -> {
                    logger.error("Error processing inbound message for session ${session.sessionId}: ${result.message}", result.cause)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to process inbound message for session ${session.sessionId}: ${e.message}", e)
        }
    }

    /**
     * Handle an outbound message from OpenClaw VPS
     */
    private suspend fun handleOutboundMessage(
        session: ProxySession,
        messageText: String
    ) {
        try {
            val frame = json.decodeFromString<WebSocketFrame>(messageText)
            session.metadata.incrementReceived()

            // Auto-handle connect.challenge from OpenClaw
            if (frame.event == "connect.challenge") {
                logger.info("Received connect.challenge from OpenClaw for session ${session.sessionId}")
                openClawConnector.handleConnectChallenge(
                    session.openclawSession!!,
                    session.metadata.clientToken,
                    session.metadata.platform ?: "unknown"
                )
                return
            }

            when (val result = messagePipeline.processOutbound(frame, session)) {
                is InterceptorResult.Continue -> {
                    val processedJson = json.encodeToString(result.frame)
                    session.clientSession.send(Frame.Text(processedJson))
                    session.metadata.incrementSent()
                }
                is InterceptorResult.Drop -> {
                    logger.debug("Dropped outbound message for session ${session.sessionId}: ${result.reason}")
                }
                is InterceptorResult.Error -> {
                    logger.error("Error processing outbound message for session ${session.sessionId}: ${result.message}", result.cause)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to process outbound message for session ${session.sessionId}: ${e.message}", e)
        }
    }

    /**
     * Close a proxy session and cleanup resources
     *
     * @param sessionId The session ID to close
     */
    suspend fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return

        logger.info("Closing session $sessionId")

        // Cancel forwarding jobs
        session.forwardingJobs?.let { (inbound, outbound) ->
            inbound.cancel()
            outbound.cancel()
        }

        // Close connections
        try {
            session.openclawSession?.close()
        } catch (e: Exception) {
            logger.error("Error closing OpenClaw session: ${e.message}", e)
        }

        try {
            session.clientSession.close()
        } catch (e: Exception) {
            logger.error("Error closing client session: ${e.message}", e)
        }

        logger.info(
            "Session $sessionId closed - Messages sent: ${session.metadata.getSentCount()}, " +
            "received: ${session.metadata.getReceivedCount()}"
        )
    }

    /**
     * Get all active sessions
     */
    fun getActiveSessions(): Map<String, ProxySession> = sessions.toMap()

    /**
     * Get session count
     */
    fun getSessionCount(): Int = sessions.size

    /**
     * Close all sessions (for graceful shutdown)
     */
    suspend fun closeAllSessions() {
        logger.info("Closing all ${sessions.size} active sessions")
        sessions.keys.toList().forEach { sessionId ->
            closeSession(sessionId)
        }
    }
}
