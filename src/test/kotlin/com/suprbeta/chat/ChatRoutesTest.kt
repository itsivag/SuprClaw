package com.suprbeta.chat

import com.suprbeta.configureSerialization
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoutesTest {
    private val chatHistoryService = mockk<ChatHistoryService>()
    private val authService = mockk<FirebaseAuthService>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.configureTestModule() {
        configureSerialization()
        install(FirebaseAuthPlugin) {
            authService = this@ChatRoutesTest.authService
        }
        configureChatRoutes(chatHistoryService)
    }

    @Test
    fun `threads route requires auth`() = testApplication {
        application { configureTestModule() }

        val response = client.get("/api/chat/threads")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `threads route returns chat thread summaries`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { chatHistoryService.listThreads("user-1", 50, null) } returns ChatThreadListResponse(
            count = 1,
            threads = listOf(
                ChatThreadView(
                    threadId = "thread-1",
                    threadKey = "agent:main:main",
                    createdAt = "2026-03-13T10:00:00Z",
                    updatedAt = "2026-03-13T10:00:01Z",
                    lastMessageAt = "2026-03-13T10:00:01Z",
                    lastRole = ChatMessageRole.ASSISTANT,
                    messageCount = 2,
                    preview = "Latest reply"
                )
            ),
            nextCursor = "cursor-1"
        )

        val response = client.get("/api/chat/threads") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString(ChatThreadListResponse.serializer(), response.bodyAsText())
        assertEquals(1, body.count)
        assertEquals("thread-1", body.threads.single().threadId)
        assertEquals("cursor-1", body.nextCursor)
    }

    @Test
    fun `messages route returns chat messages for a thread`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { chatHistoryService.listMessages("user-1", "thread-1", 200, null) } returns ChatMessageListResponse(
            count = 1,
            threadId = "thread-1",
            messages = listOf(
                ChatMessageView(
                    id = "msg-1",
                    threadId = "thread-1",
                    threadKey = "agent:main:main",
                    role = ChatMessageRole.ASSISTANT,
                    kind = ChatMessageKind.MESSAGE,
                    direction = ChatMessageDirection.OUTBOUND,
                    createdAt = "2026-03-13T10:00:01Z",
                    updatedAt = "2026-03-13T10:00:01Z",
                    content = "Hello again"
                )
            ),
            nextCursor = null
        )

        val response = client.get("/api/chat/threads/thread-1/messages") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Hello again"))
    }
}
