package com.suprbeta.chat

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ChatHistoryService {
    suspend fun captureInbound(frame: WebSocketFrame, session: ProxySession)

    suspend fun captureOutbound(frame: WebSocketFrame, session: ProxySession)

    suspend fun captureSystemError(
        session: ProxySession,
        sourceFrame: WebSocketFrame? = null,
        code: String? = null,
        message: String
    )

    suspend fun listThreads(userId: String, limit: Int, cursor: String?): ChatThreadListResponse

    suspend fun listMessages(userId: String, threadId: String, limit: Int, cursor: String?): ChatMessageListResponse

    suspend fun flushSession(sessionId: String)
}

class ChatHistoryServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val json: Json,
    application: Application
) : ChatHistoryService {
    private val logger = application.log
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeJobsBySession = ConcurrentHashMap<String, MutableSet<Job>>()
    private val assistantBuffers = ConcurrentHashMap<String, AssistantStreamBuffer>()
    private val fallbackStreamIds = ConcurrentHashMap<String, String>()
    private val generatedIdCounter = AtomicLong(0)
    private val sessionFlushLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun captureInbound(frame: WebSocketFrame, session: ProxySession) {
        if (frame.type != "req" || frame.method != "chat.send") return

        val content = extractInboundContent(frame)?.trim().orEmpty()
        if (content.isBlank()) return

        val thread = resolveThreadContext(session, frame)
        val frameId = resolveLogicalFrameId(frame, ChatMessageDirection.INBOUND)
        val messageId = stableHash("${session.metadata.userId}|${thread.threadKey}|${ChatMessageDirection.INBOUND}|$frameId")
        val now = Instant.now().toString()

        enqueueWrite(
            sessionId = session.sessionId,
            write = ChatMessageWrite(
                userId = session.metadata.userId,
                threadId = thread.threadId,
                threadKey = thread.threadKey,
                taskId = thread.taskId,
                agentId = thread.agentId,
                id = messageId,
                role = ChatMessageRole.USER,
                kind = ChatMessageKind.MESSAGE,
                direction = ChatMessageDirection.INBOUND,
                frameId = frameId,
                state = frame.state,
                complete = true,
                createdAt = now,
                updatedAt = now,
                content = content,
                rawFrameJson = json.encodeToString(WebSocketFrame.serializer(), frame)
            )
        )
    }

    override suspend fun captureOutbound(frame: WebSocketFrame, session: ProxySession) {
        if (shouldIgnoreFrame(frame)) return

        extractErrorMessage(frame)?.let { errorMessage ->
            if (!shouldIgnoreError(frame, errorMessage)) {
                captureSystemError(
                    session = session,
                    sourceFrame = frame,
                    code = extractErrorCode(frame),
                    message = errorMessage
                )
            }
            return
        }

        if (frame.event != "chat") return

        val content = extractOutboundContent(frame).orEmpty()
        val state = frame.state?.lowercase()
        val thread = resolveThreadContext(session, frame)
        val identity = resolveOutboundIdentity(session, thread, frame)
        val now = Instant.now().toString()

        if (state == "delta") {
            if (content.isBlank()) return
            val buffer = assistantBuffers.computeIfAbsent(identity.bufferKey) {
                AssistantStreamBuffer(
                    sessionId = session.sessionId,
                    thread = thread,
                    messageId = identity.messageId,
                    frameId = identity.frameId,
                    createdAt = now
                )
            }
            buffer.updatedAt = now
            buffer.state = state
            buffer.lastRawFrameJson = json.encodeToString(WebSocketFrame.serializer(), frame)
            buffer.append(content)
            return
        }

        val buffer = assistantBuffers.remove(identity.bufferKey)
        fallbackStreamIds.remove(identity.fallbackKey)
        val finalContent = buildString {
            if (buffer != null) append(buffer.content.toString())
            if (content.isNotBlank()) append(content)
        }.trim()

        if (finalContent.isBlank()) return

        val createdAt = buffer?.createdAt ?: now
        val rawFrameJson = json.encodeToString(WebSocketFrame.serializer(), frame)
        enqueueWrite(
            sessionId = session.sessionId,
            write = ChatMessageWrite(
                userId = session.metadata.userId,
                threadId = thread.threadId,
                threadKey = thread.threadKey,
                taskId = thread.taskId,
                agentId = thread.agentId,
                id = identity.messageId,
                role = ChatMessageRole.ASSISTANT,
                kind = ChatMessageKind.MESSAGE,
                direction = ChatMessageDirection.OUTBOUND,
                frameId = identity.frameId,
                state = frame.state ?: buffer?.state,
                complete = true,
                createdAt = createdAt,
                updatedAt = now,
                content = finalContent,
                rawFrameJson = rawFrameJson
            )
        )
    }

    override suspend fun captureSystemError(
        session: ProxySession,
        sourceFrame: WebSocketFrame?,
        code: String?,
        message: String
    ) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return

        val thread = resolveThreadContext(session, sourceFrame)
        val frameId = sourceFrame?.let { resolveLogicalFrameId(it, ChatMessageDirection.OUTBOUND) }
            ?: "system-error:${generatedIdCounter.incrementAndGet()}"
        val messageId = stableHash("${session.metadata.userId}|${thread.threadKey}|${ChatMessageDirection.OUTBOUND}|$frameId|error")
        val now = Instant.now().toString()
        val rawFrame = buildJsonObject {
            put("type", "res")
            put("id", sourceFrame?.id ?: frameId)
            put("ok", false)
            put("code", code ?: "")
            put("message", trimmed)
        }.toString()

        enqueueWrite(
            sessionId = session.sessionId,
            write = ChatMessageWrite(
                userId = session.metadata.userId,
                threadId = thread.threadId,
                threadKey = thread.threadKey,
                taskId = thread.taskId,
                agentId = thread.agentId,
                id = messageId,
                role = ChatMessageRole.SYSTEM,
                kind = ChatMessageKind.ERROR,
                direction = ChatMessageDirection.OUTBOUND,
                frameId = frameId,
                state = "final",
                complete = true,
                createdAt = now,
                updatedAt = now,
                content = trimmed,
                rawFrameJson = rawFrame
            )
        )
    }

    override suspend fun listThreads(userId: String, limit: Int, cursor: String?): ChatThreadListResponse {
        val safeLimit = limit.coerceIn(1, 100)
        val decodedCursor = decodeThreadCursor(cursor)
        val threads = firestoreRepository.listChatThreads(userId, safeLimit + 1, decodedCursor)
        val hasMore = threads.size > safeLimit
        val visible = if (hasMore) threads.take(safeLimit) else threads
        val nextCursor = visible.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { encodeThreadCursor(ChatThreadCursor(it.lastMessageAt, it.threadId)) }

        return ChatThreadListResponse(
            count = visible.size,
            threads = visible,
            nextCursor = nextCursor
        )
    }

    override suspend fun listMessages(userId: String, threadId: String, limit: Int, cursor: String?): ChatMessageListResponse {
        val safeLimit = limit.coerceIn(1, 200)
        val decodedCursor = decodeMessageCursor(cursor)
        val messages = firestoreRepository.listChatMessages(userId, threadId, safeLimit + 1, decodedCursor)
        val hasMore = messages.size > safeLimit
        val visible = if (hasMore) messages.take(safeLimit) else messages
        val nextCursor = visible.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { encodeMessageCursor(ChatMessageCursor(it.createdAt, it.id)) }

        return ChatMessageListResponse(
            count = visible.size,
            threadId = threadId,
            messages = visible,
            nextCursor = nextCursor
        )
    }

    override suspend fun flushSession(sessionId: String) {
        val lock = sessionFlushLocks.computeIfAbsent(sessionId) { Mutex() }
        lock.withLock {
            flushAssistantBuffers(sessionId)
            waitForWriteJobs(sessionId)
        }
    }

    private suspend fun flushAssistantBuffers(sessionId: String) {
        val buffers = assistantBuffers.entries
            .filter { it.value.sessionId == sessionId }
            .map { it.key to it.value }

        buffers.forEach { (bufferKey, buffer) ->
            assistantBuffers.remove(bufferKey)
            fallbackStreamIds.remove("${buffer.sessionId}|${buffer.thread.threadId}")
            val content = buffer.content.toString().trim()
            if (content.isBlank()) return@forEach
            enqueueWrite(
                sessionId = sessionId,
                write = ChatMessageWrite(
                    userId = buffer.thread.userId,
                    threadId = buffer.thread.threadId,
                    threadKey = buffer.thread.threadKey,
                    taskId = buffer.thread.taskId,
                    agentId = buffer.thread.agentId,
                    id = buffer.messageId,
                    role = ChatMessageRole.ASSISTANT,
                    kind = ChatMessageKind.MESSAGE,
                    direction = ChatMessageDirection.OUTBOUND,
                    frameId = buffer.frameId,
                    state = buffer.state,
                    complete = false,
                    createdAt = buffer.createdAt,
                    updatedAt = buffer.updatedAt,
                    content = content,
                    rawFrameJson = buffer.lastRawFrameJson
                )
            )
        }
    }

    private suspend fun waitForWriteJobs(sessionId: String) {
        while (true) {
            val jobs = synchronized(writeJobsBySession) {
                writeJobsBySession[sessionId]?.toList().orEmpty()
            }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    private fun enqueueWrite(sessionId: String, write: ChatMessageWrite) {
        val job = scope.launch {
            try {
                firestoreRepository.upsertChatMessage(write)
            } catch (e: Exception) {
                logger.error("Failed to persist chat history message ${write.id} for session $sessionId", e)
            }
        }
        synchronized(writeJobsBySession) {
            writeJobsBySession.getOrPut(sessionId) { mutableSetOf() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(writeJobsBySession) {
                val jobs = writeJobsBySession[sessionId] ?: return@invokeOnCompletion
                jobs.remove(job)
                if (jobs.isEmpty()) {
                    writeJobsBySession.remove(sessionId)
                }
            }
        }
    }

    private fun shouldIgnoreFrame(frame: WebSocketFrame): Boolean {
        if (frame.event == "connect.challenge") return true
        if (frame.method == "connect") return true
        if (frame.event == "disconnect") return true
        return false
    }

    private fun shouldIgnoreError(frame: WebSocketFrame, message: String): Boolean {
        if (frame.event == "connect.challenge") return true
        return message.equals("pairing required", ignoreCase = true)
    }

    private fun extractInboundContent(frame: WebSocketFrame): String? =
        extractPreferredText(frame.params)

    private fun extractOutboundContent(frame: WebSocketFrame): String? {
        val payloadContent = extractPreferredText(frame.payload)
        if (!payloadContent.isNullOrBlank()) return payloadContent
        return extractPreferredText(frame.result)
    }

    private fun extractErrorMessage(frame: WebSocketFrame): String? {
        val errorObject = frame.error as? JsonObject ?: return null
        return errorObject["message"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun extractErrorCode(frame: WebSocketFrame): String? {
        val errorObject = frame.error as? JsonObject ?: return null
        return errorObject["code"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun extractPreferredText(element: JsonElement?): String? {
        val parts = extractPreferredTextParts(element)
        if (parts.isEmpty()) return null
        return parts.joinToString(separator = "").ifBlank { null }
    }

    private fun extractPreferredTextParts(element: JsonElement?, depth: Int = 0): List<String> {
        if (element == null || depth > 4) return emptyList()

        return when (element) {
            is JsonPrimitive -> {
                val value = element.contentOrNull.orEmpty()
                if (value.isBlank()) emptyList() else listOf(value)
            }

            is JsonArray -> element.flatMap { extractPreferredTextParts(it, depth + 1) }

            is JsonObject -> {
                val preferredKeys = listOf(
                    "text",
                    "message",
                    "content",
                    "delta",
                    "input",
                    "output",
                    "outputText",
                    "response",
                    "result"
                )
                val directMatches = preferredKeys.flatMap { key ->
                    extractPreferredTextParts(element[key], depth + 1)
                }
                if (directMatches.isNotEmpty()) {
                    directMatches
                } else {
                    element.values.flatMap { child ->
                        when (child) {
                            is JsonObject, is JsonArray -> extractPreferredTextParts(child, depth + 1)
                            else -> emptyList()
                        }
                    }
                }
            }
        }
    }

    private fun resolveThreadContext(session: ProxySession, frame: WebSocketFrame?): ChatThreadContext {
        val extractedKey = frame?.let { extractSessionKey(it) }?.trim()?.ifBlank { null }
        if (!extractedKey.isNullOrBlank()) {
            session.metadata.rememberSessionKey(extractedKey)
        }

        val threadKey = extractedKey
            ?: session.metadata.getSessionKey()?.trim()?.ifBlank { null }
            ?: "session:${session.sessionId}"

        return ChatThreadContext(
            userId = session.metadata.userId,
            threadId = stableHash("${session.metadata.userId}|$threadKey"),
            threadKey = threadKey,
            taskId = parseTaskId(threadKey),
            agentId = parseAgentId(threadKey)
        )
    }

    private fun extractSessionKey(frame: WebSocketFrame): String? {
        return findFirstStringByKeys(frame.params, setOf("sessionKey", "session_key"))
            ?: findFirstStringByKeys(frame.payload, setOf("sessionKey", "session_key"))
            ?: findFirstStringByKeys(frame.result, setOf("sessionKey", "session_key"))
    }

    private fun findFirstStringByKeys(element: JsonElement?, keys: Set<String>, depth: Int = 0): String? {
        if (element == null || depth > 4) return null
        return when (element) {
            is JsonObject -> {
                keys.firstNotNullOfOrNull { key ->
                    element[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
                } ?: element.values.firstNotNullOfOrNull { findFirstStringByKeys(it, keys, depth + 1) }
            }

            is JsonArray -> element.firstNotNullOfOrNull { findFirstStringByKeys(it, keys, depth + 1) }
            else -> null
        }
    }

    private fun resolveLogicalFrameId(frame: WebSocketFrame, direction: String): String {
        val explicit = frame.id?.trim()?.ifBlank { null }
            ?: findFirstStringByKeys(frame.payload, LOGICAL_ID_KEYS)
            ?: findFirstStringByKeys(frame.result, LOGICAL_ID_KEYS)
            ?: findFirstStringByKeys(frame.params, LOGICAL_ID_KEYS)
        return explicit ?: "$direction-generated:${generatedIdCounter.incrementAndGet()}"
    }

    private fun resolveOutboundIdentity(
        session: ProxySession,
        thread: ChatThreadContext,
        frame: WebSocketFrame
    ): OutboundIdentity {
        val explicit = frame.id?.trim()?.ifBlank { null }
            ?: findFirstStringByKeys(frame.payload, LOGICAL_ID_KEYS)
            ?: findFirstStringByKeys(frame.result, LOGICAL_ID_KEYS)

        if (!explicit.isNullOrBlank()) {
            val messageId = stableHash("${session.metadata.userId}|${thread.threadKey}|${ChatMessageDirection.OUTBOUND}|$explicit")
            return OutboundIdentity(
                messageId = messageId,
                frameId = explicit,
                bufferKey = "${session.sessionId}|${thread.threadId}|$explicit",
                fallbackKey = "${session.sessionId}|${thread.threadId}"
            )
        }

        val fallbackKey = "${session.sessionId}|${thread.threadId}"
        val fallbackStreamId = fallbackStreamIds.computeIfAbsent(fallbackKey) {
            "outbound-generated:${generatedIdCounter.incrementAndGet()}"
        }
        return OutboundIdentity(
            messageId = stableHash("${session.metadata.userId}|${thread.threadKey}|${ChatMessageDirection.OUTBOUND}|$fallbackStreamId"),
            frameId = fallbackStreamId,
            bufferKey = "$fallbackKey|$fallbackStreamId",
            fallbackKey = fallbackKey
        )
    }

    private fun parseTaskId(threadKey: String): String? {
        val parts = threadKey.split(":")
        if (parts.size >= 3 && parts[0] == "hook" && parts[1] == "task") {
            return parts.drop(2).joinToString(":").ifBlank { null }
        }
        return null
    }

    private fun parseAgentId(threadKey: String): String? {
        val parts = threadKey.split(":")
        if (parts.size >= 2 && parts[0] == "agent") {
            return parts[1].ifBlank { null }
        }
        return null
    }

    private fun stableHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun encodeThreadCursor(cursor: ChatThreadCursor): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${cursor.lastMessageAt}\n${cursor.threadId}".toByteArray(Charsets.UTF_8))

    private fun decodeThreadCursor(cursor: String?): ChatThreadCursor? {
        if (cursor.isNullOrBlank()) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Invalid chat thread cursor") }
        val parts = decoded.split('\n', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw IllegalArgumentException("Invalid chat thread cursor")
        }
        return ChatThreadCursor(parts[0], parts[1])
    }

    private fun encodeMessageCursor(cursor: ChatMessageCursor): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${cursor.createdAt}\n${cursor.messageId}".toByteArray(Charsets.UTF_8))

    private fun decodeMessageCursor(cursor: String?): ChatMessageCursor? {
        if (cursor.isNullOrBlank()) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Invalid chat message cursor") }
        val parts = decoded.split('\n', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw IllegalArgumentException("Invalid chat message cursor")
        }
        return ChatMessageCursor(parts[0], parts[1])
    }

    private data class AssistantStreamBuffer(
        val sessionId: String,
        val thread: ChatThreadContext,
        val messageId: String,
        val frameId: String,
        val createdAt: String,
        val content: StringBuilder = StringBuilder(),
        var updatedAt: String = createdAt,
        var state: String? = null,
        var lastRawFrameJson: String = "{}"
    ) {
        fun append(text: String) {
            content.append(text)
        }
    }

    private data class OutboundIdentity(
        val messageId: String,
        val frameId: String,
        val bufferKey: String,
        val fallbackKey: String
    )

    private companion object {
        val LOGICAL_ID_KEYS = setOf("messageId", "message_id", "responseId", "response_id", "id")
    }
}
