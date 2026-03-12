package com.suprbeta.browser

import com.suprbeta.configureSerialization
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserRoutesTest {
    private val browserService = mockk<BrowserService>()
    private val authService = mockk<FirebaseAuthService>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.configureTestModule() {
        configureSerialization()
        install(FirebaseAuthPlugin) {
            authService = this@BrowserRoutesTest.authService
        }
        configureBrowserRoutes(browserService)
    }

    @Test
    fun `create session returns viewer url immediately`() = testApplication {
        application { configureTestModule() }

        val responseBody = BrowserSessionView(
            sessionId = "browser_123",
            taskId = "task-1",
            profileId = "profile-1",
            state = BrowserSessionState.ACTIVE,
            viewerUrl = "https://api.example.com/api/browser/sessions/browser_123/view",
            takeoverUrl = "https://api.example.com/api/browser/sessions/browser_123/takeover",
            initialUrl = "https://example.com",
            createdAt = "2026-03-12T12:00:00Z",
            updatedAt = "2026-03-12T12:00:00Z",
            expiresAt = "2026-03-12T12:30:00Z",
            activityExpiresAt = "2026-03-12T12:20:00Z",
            gracefulCloseDeadlineAt = "2026-03-12T12:28:00Z",
            takeoverDeadlineAt = "2026-03-12T12:10:00Z"
        )

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery {
            browserService.createSession(
                "user-1",
                CreateBrowserSessionRequest(
                    profileId = "profile-1",
                    taskId = "task-1",
                    initialUrl = "https://example.com",
                    takeoverTimeoutSeconds = 600
                )
            )
        } returns responseBody

        val response = client.post("/api/browser/sessions") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateBrowserSessionRequest.serializer(),
                    CreateBrowserSessionRequest(
                        profileId = "profile-1",
                        taskId = "task-1",
                        initialUrl = "https://example.com",
                        takeoverTimeoutSeconds = 600
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val decoded = json.decodeFromString(BrowserSessionView.serializer(), response.bodyAsText())
        assertEquals(responseBody.viewerUrl, decoded.viewerUrl)
        assertEquals(BrowserSessionState.ACTIVE, decoded.state)
        coVerify(exactly = 1) { browserService.createSession("user-1", any()) }
    }

    @Test
    fun `viewer route requires auth`() = testApplication {
        application { configureTestModule() }

        val response = client.get("/api/browser/sessions/browser_123/view")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `viewer route returns html page`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { browserService.getViewerPage("user-1", "browser_123", interactive = false) } returns "<html><body>viewer</body></html>"

        val response = client.get("/api/browser/sessions/browser_123/view") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("viewer"))
    }

    @Test
    fun `takeover request returns updated session`() = testApplication {
        application { configureTestModule() }

        val updated = BrowserSessionView(
            sessionId = "browser_123",
            taskId = "task-1",
            profileId = "profile-1",
            state = BrowserSessionState.TAKEOVER_REQUESTED,
            viewerUrl = "https://api.example.com/api/browser/sessions/browser_123/view",
            takeoverUrl = "https://api.example.com/api/browser/sessions/browser_123/takeover",
            createdAt = "2026-03-12T12:00:00Z",
            updatedAt = "2026-03-12T12:01:00Z",
            expiresAt = "2026-03-12T12:30:00Z",
            activityExpiresAt = "2026-03-12T12:20:00Z",
            gracefulCloseDeadlineAt = "2026-03-12T12:28:00Z",
            takeoverDeadlineAt = "2026-03-12T12:10:00Z"
        )

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { browserService.requestTakeover("user-1", "browser_123", TakeoverRequest("captcha")) } returns updated

        val response = client.post("/api/browser/sessions/browser_123/takeover-request") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TakeoverRequest.serializer(), TakeoverRequest("captcha")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val decoded = json.decodeFromString(BrowserSessionView.serializer(), response.bodyAsText())
        assertEquals(BrowserSessionState.TAKEOVER_REQUESTED, decoded.state)
    }

    @Test
    fun `reset profile delegates to service`() = testApplication {
        application { configureTestModule() }

        val profile = BrowserProfileView(
            id = "profile-1",
            label = "Primary",
            generation = 2,
            status = "active",
            createdAt = "2026-03-12T12:00:00Z",
            lastUsedAt = "2026-03-12T12:05:00Z"
        )

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { browserService.resetProfile("user-1", "profile-1") } returns profile

        val response = client.delete("/api/browser/profiles/profile-1") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val decoded = json.decodeFromString(BrowserProfileView.serializer(), response.bodyAsText())
        assertEquals(2, decoded.generation)
    }
}
