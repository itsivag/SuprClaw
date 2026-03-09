package com.suprbeta.websocket

import com.suprbeta.core.ShellEscaping.requireUuid
import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
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
 * Manages proxy sessions between mobile clients and OpenClaw VPS
 */
class ProxySessionManager(
    application: Application,
    private val openClawConnector: OpenClawConnector,
    private val messagePipeline: MessagePipeline,
    private val usageInterceptor: UsageInterceptor,
    private val json: Json,
    private val firestoreRepository: com.suprbeta.firebase.FirestoreRepository,
    private val sshCommandExecutor: SshCommandExecutor
) {
    private val logger = application.log

    private companion object {
        const val PAIRING_APPROVAL_TTL_MS = 60_000L
    }

    // Map of userId -> ProxySession
    private val sessions = ConcurrentHashMap<String, ProxySession>()
    private val pairingApprovals = ConcurrentHashMap<String, Long>()

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

            // Force a fresh upstream handshake when the mobile client reconnects.
            // Reusing a previously-connected upstream session can leave the client
            // waiting in handshake state on subsequent reconnects.
            existingSession.outboundJob?.cancel()
            existingSession.outboundJob = null
            runCatching {
                existingSession.openclawSession?.close(
                    CloseReason(CloseReason.Codes.NORMAL, "client reconnected; forcing upstream re-handshake")
                )
            }
            existingSession.openclawSession = null
            existingSession.openClawGatewayToken = null

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

    suspend fun establishOpenClawConnection(session: ProxySession): Boolean {
        if (session.isOpenClawConnected) return true // Already connected (resumed session)

        try {
            val userId = session.metadata.userId
            val userDroplet = firestoreRepository.getUserDropletInternal(userId)
            
            if (userDroplet == null || userDroplet.vpsGatewayUrl.isBlank()) {
                logger.error("No valid droplet found for user $userId")
                return false
            }
            
            logger.info("Routing user $userId to VPS: ${userDroplet.vpsGatewayUrl}")
            
            val openClawSession = openClawConnector.connect(
                token = userDroplet.gatewayToken,
                vpsGatewayUrl = userDroplet.vpsGatewayUrl
            )

            if (openClawSession == null) return false

            session.openclawSession = openClawSession
            session.openClawGatewayToken = userDroplet.gatewayToken
            return true

        } catch (e: Exception) {
            logger.error("Error establishing OpenClaw connection for user ${session.metadata.userId}: ${e.message}", e)
            return false
        }
    }

    fun startMessageForwarding(session: ProxySession, scope: CoroutineScope) {
        if (!session.isOpenClawConnected) return

        // Cancel existing inbound job if any (reconnect scenario)
        session.inboundJob?.cancel()

        // Inbound job: Mobile client → OpenClaw VPS
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

        // Only start outbound job if not already running (resumed session handles its own outbound)
        if (session.outboundJob == null || !session.outboundJob!!.isActive) {
            session.outboundJob = scope.launch {
                drainMessageQueues(session)
                runOutboundWithReconnect(session, scope)
            }
        } else {
            // Reconnected: just drain the offline queue
            scope.launch { drainMessageQueues(session) }
        }

        logger.info("Message forwarding active for user ${session.metadata.userId}")
    }

    private suspend fun runOutboundWithReconnect(session: ProxySession, scope: CoroutineScope) {
        while (true) {
            val vpsSession = session.openclawSession ?: break

            var upstreamCloseReason: CloseReason? = null

            try {
                for (frame in vpsSession.incoming) {
                    if (frame is Frame.Text) {
                        handleOutboundMessage(session, frame.readText())
                    }
                }

                upstreamCloseReason = awaitCloseReasonSafely(vpsSession)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Outbound forwarding error for user ${session.metadata.userId}: ${e.message}")
            } finally {
                if (upstreamCloseReason == null) {
                    upstreamCloseReason = awaitCloseReasonSafely(vpsSession)
                }
                logger.info(
                    "OpenClaw upstream WebSocket closed for user ${session.metadata.userId}: " +
                        describeCloseReason(upstreamCloseReason)
                )
            }

            session.openclawSession = null
            
            // If the session is fully closing (not just VPS drop), exit
            if (sessions[session.metadata.userId] == null) break

            logger.info("Attempting VPS reconnect for user ${session.metadata.userId}...")
            val reconnected = establishOpenClawConnection(session)
            if (!reconnected) {
                logger.error("VPS reconnect failed for user ${session.metadata.userId}")
                closeSession(session.metadata.userId)
                return
            }
        }
    }

    private suspend fun handleInboundMessage(session: ProxySession, messageText: String) {
        val openClawSession = session.openclawSession ?: return

        try {
            val frame = json.decodeFromString<WebSocketFrame>(messageText)
            session.metadata.incrementReceived()

            if (frame.method == "connect") return

            when (val result = messagePipeline.processInbound(frame, session)) {
                is InterceptorResult.Continue -> {
                    val processedJson = json.encodeToString(result.frame)
                    openClawSession.send(Frame.Text(processedJson))
                    session.metadata.incrementSent()
                }
                is InterceptorResult.Drop -> {}
                is InterceptorResult.Error -> throw RuntimeException(result.message)
            }
        } catch (e: Exception) {
            logger.error("Failed to process inbound message for user ${session.metadata.userId}: ${e.message}")
        }
    }

    private suspend fun handleOutboundMessage(session: ProxySession, messageText: String) {
        try {
            val frame = json.decodeFromString<WebSocketFrame>(messageText)
            session.metadata.incrementReceived()

            // Handle OpenClaw handshake
            if (frame.event == "connect.challenge") {
                val upstreamToken = session.openClawGatewayToken ?: return
                openClawConnector.handleConnectChallenge(
                    session.openclawSession!!,
                    upstreamToken,
                    frame.payload,
                    session.metadata.platform ?: "unknown"
                )
                return
            }

            if (tryApprovePairing(session, frame)) {
                return
            }

            when (val result = messagePipeline.processOutbound(frame, session)) {
                is InterceptorResult.Continue -> {
                    val processedJson = json.encodeToString(result.frame)
                    val client = session.clientSession
                    
                    if (client != null && client.isActive) {
                        try {
                            client.send(Frame.Text(processedJson))
                            session.metadata.incrementSent()
                        } catch (e: Exception) {
                            session.offlineQueue.add(processedJson)
                        }
                    } else {
                        // Client offline, queue in memory
                        session.offlineQueue.add(processedJson)
                        logger.debug("Client offline, message queued in memory for user ${session.metadata.userId}")
                    }
                }
                is InterceptorResult.Drop -> {}
                is InterceptorResult.Error -> throw RuntimeException(result.message)
            }
        } catch (e: Exception) {
            logger.error("Failed to process outbound message for user ${session.metadata.userId}: ${e.message}")
        }
    }

    private suspend fun tryApprovePairing(session: ProxySession, frame: WebSocketFrame): Boolean {
        val errorObj = frame.error?.jsonObject ?: return false
        val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: return false
        if (message != "pairing required") return false

        val requestId = runCatching {
            errorObj["details"]?.jsonObject?.get("requestId")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        if (requestId.isNullOrBlank()) {
            logger.warn("Pairing required for user ${session.metadata.userId} but requestId is missing")
            return false
        }

        val safeRequestId = runCatching {
            requireUuid(requestId, "pairing requestId")
        }.getOrNull()

        if (safeRequestId == null) {
            logger.warn("Ignoring invalid pairing requestId for user ${session.metadata.userId}")
            return false
        }

        val now = System.currentTimeMillis()
        evictExpiredPairingApprovals(now)
        val previous = pairingApprovals.put(safeRequestId, now)
        if (previous != null && now - previous < PAIRING_APPROVAL_TTL_MS) {
            logger.info("Pairing request $requestId is already being handled for user ${session.metadata.userId}")
            return true
        }

        val userDroplet = firestoreRepository.getUserDropletInternal(session.metadata.userId)
        if (userDroplet == null) {
            logger.warn("Cannot approve pairing for user ${session.metadata.userId}: no droplet found")
            pairingApprovals.remove(safeRequestId)
            return false
        }

        return try {
            approvePairingRequest(userDroplet, safeRequestId)
            logger.info("Approved runtime pairing requestId=$safeRequestId for user ${session.metadata.userId}")
            runCatching {
                session.openclawSession?.close(
                    CloseReason(CloseReason.Codes.NORMAL, "pairing approved, reconnecting")
                )
            }
            true
        } catch (e: Exception) {
            pairingApprovals.remove(safeRequestId)
            logger.error("Failed to approve runtime pairing for user ${session.metadata.userId}: ${e.message}")
            false
        }
    }

    private fun evictExpiredPairingApprovals(now: Long) {
        pairingApprovals.entries.forEach { (requestId, timestamp) ->
            if (now - timestamp >= PAIRING_APPROVAL_TTL_MS) {
                pairingApprovals.remove(requestId, timestamp)
            }
        }
    }

    private fun approvePairingRequest(userDroplet: UserDropletInternal, requestId: String) {
        val hostIp = userDroplet.ipAddress
        require(hostIp.isNotBlank()) { "Droplet IP address is missing" }

        val containerId = userDroplet.dropletName.trim()
        val isDockerContainerId = Regex("^[a-f0-9]{12,64}$").matches(containerId)

        val safeRequestId = requireUuid(requestId, "pairing requestId")
        val command = if (isDockerContainerId) {
            val approveCommand =
                "openclaw devices approve ${singleQuote(safeRequestId)} || test -s /home/openclaw/.openclaw/devices/paired.json"
            "docker exec $containerId su - openclaw -s /bin/sh -c ${singleQuote(approveCommand)}"
        } else {
            "openclaw devices approve ${singleQuote(safeRequestId)}"
        }

        sshCommandExecutor.runSshCommand(hostIp, command)
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
        session.outboundJob?.cancel()

        withTimeoutOrNull(2_000) {
            usageInterceptor.flushSession(session.sessionId)
        }

        try {
            session.openclawSession?.close()
        } catch (e: Exception) {}

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

    private fun describeCloseReason(reason: CloseReason?): String {
        if (reason == null) return "close=unavailable"

        val message = reason.message.takeIf { it.isNotBlank() } ?: "<empty>"
        return "close.code=${reason.code} close.message=$message"
    }

    private suspend fun awaitCloseReasonSafely(
        session: DefaultWebSocketSession,
        timeoutMillis: Long = 250
    ): CloseReason? {
        return runCatching { withTimeoutOrNull(timeoutMillis) { session.closeReason.await() } }.getOrNull()
    }
}
