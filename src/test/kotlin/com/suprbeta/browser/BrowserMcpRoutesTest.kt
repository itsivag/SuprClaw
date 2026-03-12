package com.suprbeta.browser

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserMcpRoutesTest {
    private val browserService = mockk<BrowserService>()
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val json = Json { ignoreUnknownKeys = true }

    private val droplet = UserDropletInternal(
        userId = "user-1",
        dropletId = 99L,
        gatewayToken = "gw-token",
        status = "active"
    )

    private fun Application.configureTestModule() {
        configureSerialization()
        configureBrowserMcpRoutes(browserService, firestoreRepository)
    }

    @Test
    fun `mcp route requires gateway auth`() = testApplication {
        application { configureTestModule() }

        val response = client.post("/api/mcp/cloud-browser") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `mcp tools list exposes cloud browser tools`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet

        val response = client.post("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tools = body["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_list_profiles" })
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_create_profile" })
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_open" })
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_exec" })
    }

    @Test
    fun `mcp sse endpoint streams message events`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet

        client.prepareGet("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))

            val channel = response.bodyAsChannel()
            assertEquals("event: endpoint", channel.readLine())
            val endpointLine = channel.readLine()
            assertEquals("", channel.readLine())

            val endpoint = endpointLine!!.removePrefix("data: ")
            assertTrue(endpoint.startsWith("/api/mcp/cloud-browser/messages?session_id="))

            val postResponse = client.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer gw-token")
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            }

            assertEquals(HttpStatusCode.Accepted, postResponse.status)
            assertEquals("event: message", channel.readLine())
            val payloadLine = channel.readLine()
            assertEquals("", channel.readLine())

            val body = json.parseToJsonElement(payloadLine!!.removePrefix("data: ")).jsonObject
            val tools = body["result"]!!.jsonObject["tools"]!!.jsonArray
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_open" })
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "cloud_browser_close" })
        }
    }

    @Test
    fun `mcp sse endpoint honors forwarded prefix for proxy clients`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet

        client.prepareGet("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
            header("X-Forwarded-Prefix", "/cloud_browser")
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val channel = response.bodyAsChannel()
            assertEquals("event: endpoint", channel.readLine())
            val endpointLine = channel.readLine()
            assertEquals("", channel.readLine())
            assertTrue(endpointLine!!.removePrefix("data: ").startsWith("/cloud_browser/messages?session_id="))
        }
    }

    @Test
    fun `mcp tool open delegates to browser service with gateway user`() = testApplication {
        application { configureTestModule() }

        val session = BrowserSessionView(
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

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet
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
        } returns session

        val response = client.post("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"tools/call",
                  "params":{
                    "name":"cloud_browser_open",
                    "arguments":{
                      "profileId":"profile-1",
                      "taskId":"task-1",
                      "initialUrl":"https://example.com",
                      "takeoverTimeoutSeconds":600
                    }
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = body["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(result["structuredContent"]!!.toString().contains("browser_123"))
        coVerify(exactly = 1) { browserService.createSession("user-1", any()) }
    }

    @Test
    fun `mcp tool open auto creates default profile when omitted`() = testApplication {
        application { configureTestModule() }

        val profile = BrowserProfileView(
            id = "browser_profile_123",
            label = "Default Browser",
            generation = 1,
            status = "active",
            createdAt = "2026-03-12T11:58:00Z",
            lastUsedAt = "2026-03-12T11:58:00Z"
        )
        val session = BrowserSessionView(
            sessionId = "browser_123",
            taskId = "task-1",
            profileId = profile.id,
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

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet
        coEvery { browserService.listProfiles("user-1") } returns BrowserProfileListResponse(count = 0, profiles = emptyList())
        coEvery { browserService.createProfile("user-1", CreateBrowserProfileRequest(label = "Default Browser")) } returns profile
        coEvery {
            browserService.createSession(
                "user-1",
                CreateBrowserSessionRequest(
                    profileId = profile.id,
                    taskId = "task-1",
                    initialUrl = "https://example.com",
                    takeoverTimeoutSeconds = null
                )
            )
        } returns session

        val response = client.post("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "jsonrpc":"2.0",
                  "id":3,
                  "method":"tools/call",
                  "params":{
                    "name":"cloud_browser_open",
                    "arguments":{
                      "taskId":"task-1",
                      "initialUrl":"https://example.com"
                    }
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = body["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(result["structuredContent"]!!.toString().contains(profile.id))
        coVerify(exactly = 1) { browserService.listProfiles("user-1") }
        coVerify(exactly = 1) { browserService.createProfile("user-1", CreateBrowserProfileRequest(label = "Default Browser")) }
        coVerify(exactly = 1) {
            browserService.createSession(
                "user-1",
                CreateBrowserSessionRequest(
                    profileId = profile.id,
                    taskId = "task-1",
                    initialUrl = "https://example.com",
                    takeoverTimeoutSeconds = null
                )
            )
        }
    }

    @Test
    fun `mcp tool exec delegates to browser service with structured response`() = testApplication {
        application { configureTestModule() }

        val execResponse = BrowserExecResponse(
            status = "ok",
            sessionId = "browser_123",
            page = BrowserPageInfo(
                url = "https://example.com",
                title = "Example Domain",
                viewportMode = "desktop"
            ),
            signals = BrowserSignals(loginDetected = true),
            summary = BrowserSummary(
                visibleText = "Example Domain",
                primaryActions = listOf("More information...")
            ),
            execution = BrowserExecutionOutput(
                language = "bash",
                stdout = "{\"title\":\"Example Domain\"}",
                exitCode = 0
            )
        )

        coEvery { firestoreRepository.getUserDropletInternalByGatewayToken("gw-token") } returns droplet
        coEvery {
            browserService.executeSession(
                "user-1",
                BrowserExecRequest(
                    sessionId = "browser_123",
                    code = "snapshot",
                    language = "bash",
                    timeoutSeconds = 45
                )
            )
        } returns execResponse

        val response = client.post("/api/mcp/cloud-browser") {
            header(HttpHeaders.Authorization, "Bearer gw-token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "method":"tools/call",
                  "params":{
                    "name":"cloud_browser_exec",
                    "arguments":{
                      "sessionId":"browser_123",
                      "action":"snapshot",
                      "timeoutSeconds":45
                    }
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = body["result"]!!.jsonObject
        val structuredContent = result["structuredContent"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("ok", structuredContent["status"]!!.jsonPrimitive.content)
        assertEquals(
            "Example Domain",
            structuredContent["page"]!!.jsonObject["title"]!!.jsonPrimitive.content
        )
        coVerify(exactly = 1) {
            browserService.executeSession(
                "user-1",
                BrowserExecRequest(
                    sessionId = "browser_123",
                    code = "snapshot",
                    language = "bash",
                    timeoutSeconds = 45
                )
            )
        }
    }
}
