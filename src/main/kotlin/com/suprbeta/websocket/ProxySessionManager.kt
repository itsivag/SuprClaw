package com.suprbeta.websocket

import com.suprbeta.browser.BrowserClientBridge
import com.suprbeta.browser.BrowserSessionEventPayload
import com.suprbeta.chat.ChatHistoryService
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.runtime.PicoClawChatBridge
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.SessionMetadata
import com.suprbeta.websocket.models.WebSocketFrame
import com.suprbeta.websocket.pipeline.InterceptorResult
import com.suprbeta.websocket.pipeline.MessagePipeline
import com.suprbeta.websocket.pipeline.UsageInterceptor
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages proxy sessions between mobile clients and the per-user runtime bridge.
 */
class ProxySessionManager(
    application: Application,
    private val messagePipeline: MessagePipeline,
    private val usageInterceptor: UsageInterceptor,
    private val json: Json,
    private val chatHistoryService: ChatHistoryService,
    private val firestoreRepository: com.suprbeta.firebase.FirestoreRepository,
    private val picoClawChatBridge: PicoClawChatBridge
) : BrowserClientBridge {
    private val logger = application.log

    // Map of userId -> ProxySession
    private val sessions = ConcurrentHashMap<String, ProxySession>()

    override suspend fun publishEvent(userId: String, payload: BrowserSessionEventPayload) {
        val session = sessions[userId] ?: return
        val frame = WebSocketFrame(
            type = "event",
            event = payload.browserEventType,
            payload = json.encodeToJsonElement(BrowserSessionEventPayload.serializer(), payload)
        )
        deliverToClient(session, json.encodeToString(frame))
    }

    override fun resolveTaskId(userId: String): String? = sessions[userId]?.metadata?.getTaskId()

    /**
     * Create or resume a proxy session for a mobile client
     */
    suspend fun getOrCreateSession(
        clientSession: DefaultWebSocketSession,
        token: String,
        platform: String? = null,
        userId: String,
        userEmail: String?,
        emailVerified: Boolean,
        authProvider: String?,
        userTier: String
    ): ProxySession {
        val existingSession = sessions[userId]

        if (existingSession != null) {
            logger.info("Resuming existing session for user: $userId (Tier: $userTier)")
            existingSession.disconnectJob?.cancel()
            existingSession.disconnectJob = null
            existingSession.inboundJob?.cancel()

            existingSession.clientSession = clientSession
            // Optionally, update userTier if they upgraded mid-session
            // We'd need to make metadata vars mutable if we want that, 
            // but for now, they get new limits on full reconnect.
            return existingSession
        }

        // Calculate current week credits usage for rate limiting
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val dayOfWeek = today.dayOfWeek.value
        val weekStart = today.minusDays((dayOfWeek - 1).toLong()) // Monday
        val weekEnd = today
        
        var currentWeeklyCredits = 0L
        var currentDate = weekStart
        while (!currentDate.isAfter(weekEnd)) {
            currentWeeklyCredits += firestoreRepository.getDailyCreditUsage(userId, currentDate.toString())
            currentDate = currentDate.plusDays(1)
        }

        val metadata = SessionMetadata(
            clientToken = token,
            userId = userId,
            userEmail = userEmail,
            emailVerified = emailVerified,
            authProvider = authProvider,
            userTier = userTier,
            platform = platform
        )
        metadata.currentWeeklyCredits.set(currentWeeklyCredits)

        val session = ProxySession(
            clientSession = clientSession,
            metadata = metadata
        )

        sessions[userId] = session
        logger.info("Created new proxy session ${session.sessionId} for user: $userId (${userEmail ?: "no email"})")

        return session
    }

    suspend fun establishRuntimeConnection(session: ProxySession): Boolean {
        try {
            val userId = session.metadata.userId
            val userDroplet = firestoreRepository.getUserDropletInternal(userId)
            
            if (userDroplet == null || userDroplet.vpsGatewayUrl.isBlank()) {
                logger.error("No valid droplet found for user $userId")
                return false
            }
            logger.info("Routing user $userId through direct picoclaw bridge")
            return true
        } catch (e: Exception) {
            logger.error("Error establishing runtime connection for user ${session.metadata.userId}: ${e.message}", e)
            return false
        }
    }

    fun startMessageForwarding(session: ProxySession, scope: CoroutineScope) {
        // Cancel existing inbound job if any (reconnect scenario)
        session.inboundJob?.cancel()

        // Inbound job: Mobile client -> runtime bridge
        session.inboundJob = scope.launch {
            try {
                val currentClientSession = session.clientSession ?: return@launch
                for (frame in currentClientSession.incoming) {
                    if (frame is Frame.Text) {
                        handleInboundMessage(session, frame.readText())
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Inbound forwarding error for user ${session.metadata.userId}: ${e.message}")
                }
            } finally {
                logger.info("Inbound forwarding stopped for user ${session.metadata.userId}")
            }
        }

        scope.launch { drainMessageQueues(session) }

        logger.info("Message forwarding active for user ${session.metadata.userId}")
    }

    private suspend fun handleInboundMessage(session: ProxySession, messageText: String) {
        try {
            val frame = json.decodeFromString<WebSocketFrame>(messageText)
            session.metadata.incrementReceived()

            if (frame.method == "connect") return
            val userDroplet = firestoreRepository.getUserDropletInternal(session.metadata.userId) ?: return

            when (val result = messagePipeline.processInbound(frame, session)) {
                is InterceptorResult.Continue -> {
                    runCatching { chatHistoryService.captureInbound(result.frame, session) }
                        .onFailure {
                            logger.error("Failed to capture inbound chat history for user ${session.metadata.userId}", it)
                        }
                    handlePicoClawInbound(session, userDroplet, result.frame)
                }
                is InterceptorResult.Drop -> {}
                is InterceptorResult.Error -> throw RuntimeException(result.message)
            }
        } catch (e: Exception) {
            logger.error("Failed to process inbound message for user ${session.metadata.userId}: ${e.message}")
        }
    }

    private suspend fun handlePicoClawInbound(
        session: ProxySession,
        droplet: UserDropletInternal,
        frame: WebSocketFrame
    ) {
        if (frame.type == "req") {
            val sessionKey = extractSessionKey(frame) ?: session.metadata.getSessionKey() ?: "agent:main:main"
            when (frame.method) {
                "chat.send" -> {
                    val message = extractInboundText(frame) ?: return
                    session.metadata.rememberSessionKey(sessionKey)
                    val responseFrame = picoClawChatBridge.runChat(
                        droplet = droplet,
                        sessionKey = sessionKey,
                        message = message,
                        requestId = frame.id
                    )
                    runCatching { chatHistoryService.captureOutbound(responseFrame, session) }
                        .onFailure {
                            logger.error("Failed to capture outbound chat history for user ${session.metadata.userId}", it)
                        }
                    deliverToClient(session, json.encodeToString(responseFrame))
                    return
                }

                "chat.abort" -> {
                    session.metadata.rememberSessionKey(sessionKey)
                    deliverToClient(session, json.encodeToString(buildPicoClawAbortAck(frame.id, sessionKey)))
                    return
                }
            }
        }

        val errorFrame = WebSocketFrame(
            type = "res",
            id = frame.id,
            ok = false,
            error = buildJsonObject {
                put("message", "Unsupported request for picoclaw runtime")
            }
        )
        deliverToClient(session, json.encodeToString(errorFrame))
    }

    private suspend fun deliverToClient(session: ProxySession, message: String) {
        val client = session.clientSession

        if (client != null && client.isActive) {
            try {
                client.send(Frame.Text(message))
                session.metadata.incrementSent()
                return
            } catch (_: Exception) {
                session.offlineQueue.add(message)
                return
            }
        }

        session.offlineQueue.add(message)
        logger.debug("Client offline, message queued in memory for user ${session.metadata.userId}")
    }

    private fun extractSessionKey(frame: WebSocketFrame): String? {
        return findString(frame.params, setOf("sessionKey", "session_key"))
            ?: findString(frame.payload, setOf("sessionKey", "session_key"))
            ?: findString(frame.result, setOf("sessionKey", "session_key"))
    }

    private fun extractInboundText(frame: WebSocketFrame): String? =
        findString(frame.params, setOf("text", "message", "content", "input"))

    private fun findString(element: JsonElement?, keys: Set<String>, depth: Int = 0): String? {
        if (element == null || depth > 4) return null
        return when (element) {
            is JsonObject -> {
                keys.firstNotNullOfOrNull { key ->
                    element[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
                } ?: element.values.firstNotNullOfOrNull { findString(it, keys, depth + 1) }
            }

            is JsonArray -> element.firstNotNullOfOrNull { findString(it, keys, depth + 1) }
            else -> null
        }
    }

    private suspend fun drainMessageQueues(session: ProxySession) {
        val client = session.clientSession ?: return
        
        // 1. Drain In-Memory Queue (Offline messages)
        while (session.offlineQueue.isNotEmpty()) {
            val msg = session.offlineQueue.peek() ?: break
            try {
                client.send(Frame.Text(msg))
                session.offlineQueue.poll()
                session.metadata.incrementSent()
            } catch (e: Exception) {
                return // Stop draining if client disconnected again
            }
        }

        // 2. Drain Persistent Firestore Queue (Legacy fallback)
        val pending = firestoreRepository.getQueuedMessages(session.metadata.userId)
        if (pending.isEmpty()) return
        
        val delivered = mutableListOf<String>()
        for (msg in pending) {
            try {
                client.send(Frame.Text(msg.payload))
                delivered += msg.docId
                session.metadata.incrementSent()
            } catch (e: Exception) {
                break
            }
        }
        if (delivered.isNotEmpty()) firestoreRepository.deleteQueuedMessages(session.metadata.userId, delivered)
    }

    /**
     * Handles client disconnection by starting a 5-minute grace period
     */
    fun handleClientDisconnect(userId: String, scope: CoroutineScope) {
        val session = sessions[userId] ?: return
        
        logger.info("Client disconnected for user $userId. Starting 5-minute grace period.")
        session.clientSession = null
        session.inboundJob?.cancel()
        
        session.disconnectJob = scope.launch {
            delay(5 * 60 * 1000) // 5 minutes
            logger.info("Grace period expired for user $userId. Closing session.")
            closeSession(userId)
        }
    }

    suspend fun closeSession(userId: String) {
        val session = sessions.remove(userId) ?: return

        session.disconnectJob?.cancel()
        session.inboundJob?.cancel()

        withTimeoutOrNull(2_000) {
            usageInterceptor.flushSession(session.sessionId)
        }
        withTimeoutOrNull(2_000) {
            chatHistoryService.flushSession(session.sessionId)
        }

        try {
            session.clientSession?.close()
        } catch (e: Exception) {}

        logger.info("Session closed for user $userId")
    }

    fun getActiveSessions(): Map<String, ProxySession> = sessions.toMap()
    fun getSessionCount(): Int = sessions.size

    suspend fun closeAllSessions() {
        sessions.keys.toList().forEach { userId ->
            closeSession(userId)
        }
    }
}

internal fun buildPicoClawAbortAck(requestId: String?, sessionKey: String): WebSocketFrame =
    WebSocketFrame(
        type = "res",
        id = requestId,
        ok = true,
        result = buildJsonObject {
            put("sessionKey", sessionKey)
            put("accepted", true)
            put("cancelled", false)
            put("message", "Abort acknowledged; the current picoclaw CLI bridge cannot interrupt an in-flight run")
        }
    )
