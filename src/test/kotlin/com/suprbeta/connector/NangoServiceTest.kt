package com.suprbeta.connector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import kotlinx.serialization.json.JsonPrimitive
import io.mockk.mockk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NangoServiceTest {
    private val application = mockk<Application>(relaxed = true)

    @Test
    fun `createConnectSession parses token and allowed integration request`() = kotlinx.coroutines.test.runTest {
        val engine = MockEngine { request ->
            assertEquals("api.nango.dev", request.url.host)
            assertEquals("/connect/sessions", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer secret-key", request.headers[HttpHeaders.Authorization])
            respond(
                content = """
                    {
                      "data": {
                        "token": "session-token",
                        "connect_link": "https://connect.nango.dev/?session_token=session-token",
                        "expires_at": "2026-03-15T12:00:00Z"
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json()
            }
        }
        val service = NangoService(
            httpClient = client,
            application = application,
            envOverride = {
                when (it) {
                    "NANGO_SECRET_KEY" -> "secret-key"
                    else -> null
                }
            }
        )

        val response = service.createConnectSession(
            provider = NangoProviderDefinition("google", "Google", "google-prod"),
            tags = mapOf("end_user_id" to "user-123")
        )

        assertEquals("session-token", response.token)
        assertEquals("https://connect.nango.dev/?session_token=session-token", response.connectLink)
        assertEquals("2026-03-15T12:00:00Z", response.expiresAt)
    }

    @Test
    fun `verifyWebhookSignature accepts valid hmac and rejects invalid`() {
        val service = NangoService(
            httpClient = HttpClient(MockEngine { error("HTTP client should not be used") }),
            application = application,
            envOverride = {
                when (it) {
                    "NANGO_SECRET_KEY" -> "secret-key"
                    "NANGO_WEBHOOK_SECRET" -> "webhook-secret"
                    else -> null
                }
            }
        )

        val body = """{"type":"auth","success":true}"""
        val valid = hmacSha256("webhook-secret", body)

        assertTrue(service.verifyWebhookSignature(body, valid))
        assertFalse(service.verifyWebhookSignature(body, "bad-signature"))
    }

    @Test
    fun `listActionTools filters actions for one integration`() = kotlinx.coroutines.test.runTest {
        val engine = MockEngine { request ->
            assertEquals("/scripts/config", request.url.encodedPath)
            assertEquals("nango", request.url.parameters["format"])
            respond(
                content = """
                    [
                      {
                        "providerConfigKey": "google-prod",
                        "actions": [
                          {
                            "name": "create-event",
                            "description": "Create an event",
                            "json_schema": {
                              "type": "object",
                              "properties": {
                                "summary": { "type": "string" }
                              },
                              "required": ["summary"]
                            }
                          }
                        ],
                        "syncs": [],
                        "on-events": []
                      },
                      {
                        "providerConfigKey": "github-prod",
                        "actions": [
                          {
                            "name": "create-issue",
                            "description": "Create an issue",
                            "json_schema": {
                              "type": "object",
                              "properties": {
                                "title": { "type": "string" }
                              },
                              "required": ["title"]
                            }
                          }
                        ],
                        "syncs": [],
                        "on-events": []
                      }
                    ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val service = NangoService(
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } },
            application = application,
            envOverride = {
                when (it) {
                    "NANGO_SECRET_KEY" -> "secret-key"
                    else -> null
                }
            }
        )

        val tools = service.listActionTools("google-prod")

        assertEquals(1, tools.size)
        assertEquals("create-event", tools.single().name)
        assertEquals("Create an event", tools.single().description)
        assertEquals("object", tools.single().parameters["type"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `triggerAction returns async metadata when requested`() = kotlinx.coroutines.test.runTest {
        val engine = MockEngine { request ->
            assertEquals("/action/trigger", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("google-prod", request.headers["Provider-Config-Key"])
            assertEquals("conn-1", request.headers["Connection-Id"])
            assertEquals("true", request.headers["X-Async"])
            respond(
                content = """{"id":"action-123","statusUrl":"https://api.nango.dev/action/action-123"}""",
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val service = NangoService(
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } },
            application = application,
            envOverride = {
                when (it) {
                    "NANGO_SECRET_KEY" -> "secret-key"
                    else -> null
                }
            }
        )

        val result = service.triggerAction(
            providerConfigKey = "google-prod",
            connectionId = "conn-1",
            actionName = "create-event",
            async = true,
            maxRetries = 2
        )

        assertTrue(result.async)
        assertEquals("action-123", result.actionId)
        assertEquals("https://api.nango.dev/action/action-123", result.statusUrl)
        assertEquals(null, result.output)
    }

    private fun hmacSha256(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
