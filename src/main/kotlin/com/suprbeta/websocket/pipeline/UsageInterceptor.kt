package com.suprbeta.websocket.pipeline

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.TokenUsageDelta
import com.suprbeta.websocket.models.WebSocketFrame
import com.suprbeta.websocket.usage.TokenCalculator
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks usage asynchronously and persists daily token aggregates to Firestore.
 */
class UsageInterceptor(
    private val firestoreRepository: FirestoreRepository,
    private val tokenCalculator: TokenCalculator,
    application: Application
) : MessageInterceptor {
    companion object {
        private const val WORKER_COUNT = 2
        private const val FLUSH_THRESHOLD = 5
    }

    private val logger = application.log
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<UsageWorkItem>(Channel.UNLIMITED)
    private val pendingQueueBySession = ConcurrentHashMap<String, AtomicInteger>()
    private val modelCache = ConcurrentHashMap<String, String>()
    private val sessionBuffers = ConcurrentHashMap<String, SessionDayBuffer>()
    private val writeJobsBySession = ConcurrentHashMap<String, MutableSet<Job>>()

    init {
        repeat(WORKER_COUNT) {
            scope.launch {
                for (workItem in queue) {
                    try {
                        processWorkItem(workItem)
                    } catch (e: Exception) {
                        logger.error(
                            "Usage processing failed for session ${workItem.sessionId}: ${e.message}",
                            e
                        )
                    } finally {
                        pendingQueueBySession[workItem.sessionId]?.decrementAndGet()
                    }
                }
            }
        }
    }

    override suspend fun intercept(
        frame: WebSocketFrame,
        direction: MessageDirection,
        session: ProxySession
    ): InterceptorResult {
        val userId = session.metadata.userId
        if (userId.isBlank()) {
            return InterceptorResult.Error("Missing userId for session ${session.sessionId}")
        }

        val agentId = extractAgentId(frame) ?: return InterceptorResult.Continue(frame)

        val workItem = UsageWorkItem(
            sessionId = session.sessionId,
            userId = userId,
            agentId = agentId,
            direction = direction,
            frame = frame
        )

        val pendingCounter = pendingQueueBySession.computeIfAbsent(session.sessionId) { AtomicInteger(0) }
        pendingCounter.incrementAndGet()
        val result = queue.trySend(workItem)
        if (result.isFailure) {
            pendingCounter.decrementAndGet()
            logger.error("Failed to enqueue usage work for session ${session.sessionId}: ${result.exceptionOrNull()?.message}")
        }

        return InterceptorResult.Continue(frame)
    }

    suspend fun flushSession(sessionId: String, timeoutMillis: Long = 2_000) {
        withTimeoutOrNull(timeoutMillis) {
            while ((pendingQueueBySession[sessionId]?.get() ?: 0) > 0) {
                delay(25)
            }

            while (true) {
                val snapshots = snapshotSessionBuffers(sessionId)
                if (snapshots.isEmpty()) break

                snapshots.forEach { snapshot ->
                    launchWrite(snapshot)
                }
                waitForWriteJobs(sessionId)
            }
        } ?: logger.warn("Timed out while flushing usage data for session $sessionId")
    }

    private suspend fun processWorkItem(workItem: UsageWorkItem) {
        val model = resolveModel(workItem.userId, workItem.agentId)
        val tokens = tokenCalculator.countFrameTokens(workItem.frame, model)
        if (tokens <= 0L) return

        val dayUtc = currentDayUtc()
        val key = bufferKey(workItem.sessionId, dayUtc)
        val buffer = sessionBuffers.computeIfAbsent(key) {
            SessionDayBuffer(userId = workItem.userId, dayUtc = dayUtc)
        }

        var snapshot: UsageSnapshot? = null
        buffer.mutex.withLock {
            val delta = when (workItem.direction) {
                MessageDirection.INBOUND -> TokenUsageDelta.inbound(tokens)
                MessageDirection.OUTBOUND -> TokenUsageDelta.outbound(tokens)
            }
            buffer.pending = buffer.pending.plus(delta)
            buffer.pendingEvents += delta.usageEvents.toInt()

            if (buffer.pendingEvents >= FLUSH_THRESHOLD) {
                snapshot = buffer.createSnapshot(workItem.sessionId)
            }
        }

        snapshot?.let { launchWrite(it) }
    }

    private suspend fun resolveModel(userId: String, agentId: String): String {
        val cacheKey = "$userId:$agentId"
        modelCache[cacheKey]?.let { return it }

        val model = runCatching {
            firestoreRepository.getUserAgentByAgentId(userId, agentId)
                ?.model
                ?.ifBlank { TokenCalculator.DEFAULT_MODEL }
                ?: TokenCalculator.DEFAULT_MODEL
        }.getOrElse { error ->
            logger.warn("Agent model lookup failed for user=$userId agentId=$agentId: ${error.message}")
            TokenCalculator.DEFAULT_MODEL
        }

        modelCache[cacheKey] = model
        return model
    }

    private fun extractAgentId(frame: WebSocketFrame): String? {
        val params = frame.params as? JsonObject ?: return null
        val sessionKey = params["sessionKey"]?.jsonPrimitive?.contentOrNull ?: return null

        val parts = sessionKey.split(":")
        if (parts.size < 3 || parts[0] != "agent") return null

        val agentId = parts[1].trim()
        return agentId.takeIf { it.isNotEmpty() }
    }

    private fun launchWrite(snapshot: UsageSnapshot) {
        val writeJob = scope.launch(Dispatchers.IO) {
            try {
                firestoreRepository.incrementUserTokenUsageDaily(
                    userId = snapshot.userId,
                    dayUtc = snapshot.dayUtc,
                    delta = snapshot.delta,
                    sessionId = snapshot.sessionId
                )
            } catch (e: Exception) {
                logger.error(
                    "Usage write failed for session ${snapshot.sessionId} day=${snapshot.dayUtc}: ${e.message}",
                    e
                )
                restoreSnapshot(snapshot)
            }
        }

        synchronized(writeJobsBySession) {
            writeJobsBySession.getOrPut(snapshot.sessionId) { mutableSetOf() }.add(writeJob)
        }

        writeJob.invokeOnCompletion {
            synchronized(writeJobsBySession) {
                val jobs = writeJobsBySession[snapshot.sessionId] ?: return@invokeOnCompletion
                jobs.remove(writeJob)
                if (jobs.isEmpty()) {
                    writeJobsBySession.remove(snapshot.sessionId)
                }
            }
        }
    }

    private suspend fun restoreSnapshot(snapshot: UsageSnapshot) {
        val key = bufferKey(snapshot.sessionId, snapshot.dayUtc)
        val buffer = sessionBuffers.computeIfAbsent(key) {
            SessionDayBuffer(userId = snapshot.userId, dayUtc = snapshot.dayUtc)
        }

        buffer.mutex.withLock {
            buffer.pending = buffer.pending.plus(snapshot.delta)
            buffer.pendingEvents += snapshot.delta.usageEvents.toInt()
        }
    }

    private suspend fun snapshotSessionBuffers(sessionId: String): List<UsageSnapshot> {
        val prefix = "$sessionId|"
        val keys = sessionBuffers.keys.filter { it.startsWith(prefix) }
        val snapshots = mutableListOf<UsageSnapshot>()

        for (key in keys) {
            val buffer = sessionBuffers[key] ?: continue
            buffer.mutex.withLock {
                buffer.createSnapshot(sessionId)?.let { snapshots.add(it) }
            }
            if (buffer.pending.isZero()) {
                sessionBuffers.remove(key, buffer)
            }
        }

        return snapshots
    }

    private suspend fun waitForWriteJobs(sessionId: String) {
        while (true) {
            val jobs = synchronized(writeJobsBySession) {
                writeJobsBySession[sessionId]?.toList().orEmpty()
            }
            if (jobs.isEmpty()) break
            jobs.forEach { it.join() }
        }
    }

    private fun currentDayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private fun bufferKey(sessionId: String, dayUtc: String): String = "$sessionId|$dayUtc"

    private data class UsageWorkItem(
        val sessionId: String,
        val userId: String,
        val agentId: String,
        val direction: MessageDirection,
        val frame: WebSocketFrame
    )

    private data class UsageSnapshot(
        val sessionId: String,
        val userId: String,
        val dayUtc: String,
        val delta: TokenUsageDelta
    )

    private data class SessionDayBuffer(
        val userId: String,
        val dayUtc: String,
        val mutex: Mutex = Mutex(),
        var pending: TokenUsageDelta = TokenUsageDelta(),
        var pendingEvents: Int = 0
    ) {
        fun createSnapshot(sessionId: String): UsageSnapshot? {
            if (pending.isZero()) return null
            val snapshot = UsageSnapshot(
                sessionId = sessionId,
                userId = userId,
                dayUtc = dayUtc,
                delta = pending
            )
            pending = TokenUsageDelta()
            pendingEvents = 0
            return snapshot
        }
    }
}
