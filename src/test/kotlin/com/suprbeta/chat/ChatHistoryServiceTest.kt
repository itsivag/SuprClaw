package com.suprbeta.chat

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.websocket.models.ProxySession
import com.suprbeta.websocket.models.SessionMetadata
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryServiceTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun newSession(): ProxySession {
        val metadata = SessionMetadata(
            clientToken = "token",
            userId = "user-1",
            userEmail = "user@example.com",
            emailVerified = true,
            authProvider = "google.com",
            userTier = "free"
        )
        return ProxySession(clientSession = null, metadata = metadata)
    }

    @Test
    fun `captureInbound persists chat send prompt`() = testApplication {
        val writes = mutableListOf<ChatMessageWrite>()
        coEvery { firestoreRepository.upsertChatMessage(any()) } answers {
            writes += firstArg<ChatMessageWrite>()
        }
        val service = ChatHistoryServiceImpl(firestoreRepository, json, application)
        val session = newSession()

        service.captureInbound(
            frame = WebSocketFrame(
                type = "req",
                id = "req-1",
                method = "chat.send",
                params = buildJsonObject {
                    put("sessionKey", "agent:main:main")
                    put("message", "Hello world")
                }
            ),
            session = session
        )
        service.flushSession(session.sessionId)

        assertEquals(1, writes.size)
        assertEquals(ChatMessageRole.USER, writes.single().role)
        assertEquals(ChatMessageDirection.INBOUND, writes.single().direction)
        assertEquals("agent:main:main", writes.single().threadKey)
        assertEquals("Hello world", writes.single().content)
        assertEquals("agent:main:main", session.metadata.getSessionKey())
    }

    @Test
    fun `captureOutbound aggregates delta and final into one assistant message`() = testApplication {
        val writes = mutableListOf<ChatMessageWrite>()
        coEvery { firestoreRepository.upsertChatMessage(any()) } answers {
            writes += firstArg<ChatMessageWrite>()
        }
        val service = ChatHistoryServiceImpl(firestoreRepository, json, application)
        val session = newSession().also {
            it.metadata.rememberSessionKey("agent:main:main")
        }

        service.captureOutbound(
            frame = WebSocketFrame(
                id = "resp-1",
                event = "chat",
                state = "delta",
                payload = buildJsonObject { put("text", "Hello") }
            ),
            session = session
        )

        coVerify(exactly = 0) { firestoreRepository.upsertChatMessage(any()) }

        service.captureOutbound(
            frame = WebSocketFrame(
                id = "resp-1",
                event = "chat",
                state = "final",
                payload = buildJsonObject { put("text", " world") }
            ),
            session = session
        )
        service.flushSession(session.sessionId)

        assertEquals(1, writes.size)
        assertEquals(ChatMessageRole.ASSISTANT, writes.single().role)
        assertTrue(writes.single().complete)
        assertEquals("Hello world", writes.single().content)
    }

    @Test
    fun `flushSession persists partial assistant message when final is missing`() = testApplication {
        val writes = mutableListOf<ChatMessageWrite>()
        coEvery { firestoreRepository.upsertChatMessage(any()) } answers {
            writes += firstArg<ChatMessageWrite>()
        }
        val service = ChatHistoryServiceImpl(firestoreRepository, json, application)
        val session = newSession().also {
            it.metadata.rememberSessionKey("agent:main:main")
        }

        service.captureOutbound(
            frame = WebSocketFrame(
                id = "resp-partial",
                event = "chat",
                state = "delta",
                payload = buildJsonObject { put("text", "partial reply") }
            ),
            session = session
        )
        service.flushSession(session.sessionId)

        assertEquals(1, writes.size)
        assertFalse(writes.single().complete)
        assertEquals("partial reply", writes.single().content)
    }

    @Test
    fun `captureSystemError persists user visible system message`() = testApplication {
        val writes = mutableListOf<ChatMessageWrite>()
        coEvery { firestoreRepository.upsertChatMessage(any()) } answers {
            writes += firstArg<ChatMessageWrite>()
        }
        val service = ChatHistoryServiceImpl(firestoreRepository, json, application)
        val session = newSession()

        service.captureSystemError(
            session = session,
            sourceFrame = WebSocketFrame(
                type = "req",
                id = "req-9",
                method = "chat.send",
                params = buildJsonObject { put("sessionKey", "agent:main:main") }
            ),
            code = "QUOTA_EXCEEDED",
            message = "Weekly credit limit exceeded"
        )
        service.flushSession(session.sessionId)

        assertEquals(1, writes.size)
        assertEquals(ChatMessageRole.SYSTEM, writes.single().role)
        assertEquals(ChatMessageKind.ERROR, writes.single().kind)
        assertEquals("Weekly credit limit exceeded", writes.single().content)
        assertEquals("agent:main:main", writes.single().threadKey)
    }

    @Test
    fun `captureOutbound ignores pairing required protocol errors`() = testApplication {
        coEvery { firestoreRepository.upsertChatMessage(any()) } answers {}
        val service = ChatHistoryServiceImpl(firestoreRepository, json, application)
        val session = newSession()

        service.captureOutbound(
            frame = WebSocketFrame(
                type = "res",
                ok = false,
                error = buildJsonObject { put("message", "pairing required") }
            ),
            session = session
        )
        service.flushSession(session.sessionId)

        coVerify(exactly = 0) { firestoreRepository.upsertChatMessage(any()) }
    }
}
