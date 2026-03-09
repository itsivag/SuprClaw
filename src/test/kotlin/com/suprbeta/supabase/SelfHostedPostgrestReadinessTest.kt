package com.suprbeta.supabase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfHostedPostgrestReadinessTest {

    @Test
    fun `extractPostgrestErrorCode reads code from JSON response`() {
        assertEquals("PGRST002", extractPostgrestErrorCode("""{"code":"PGRST002","message":"warming"}"""))
        assertEquals(null, extractPostgrestErrorCode("not-json"))
    }

    @Test
    fun `isTransientPostgrestSchemaCacheProbeFailure matches transient PostgREST and gateway states`() {
        assertTrue(isTransientPostgrestSchemaCacheProbeFailure(503, """{"code":"PGRST002"}"""))
        assertTrue(isTransientPostgrestSchemaCacheProbeFailure(404, """{"code":"PGRST205"}"""))
        assertTrue(isTransientPostgrestSchemaCacheProbeFailure(502, "bad gateway"))
        assertFalse(isTransientPostgrestSchemaCacheProbeFailure(401, """{"code":"PGRST301"}"""))
    }

    @Test
    fun `waitForPostgrestSchemaCacheReady retries until schema query succeeds`() = runTest {
        var attempts = 0
        val client = HttpClient(MockEngine { request ->
            attempts += 1
            assertEquals("svc-key", request.headers["apikey"])
            assertEquals("Bearer svc-key", request.headers["Authorization"])
            assertEquals("proj_abc12345", request.headers["Accept-Profile"])

            if (attempts < 3) {
                respond(
                    content = """{"code":"PGRST002","message":"Could not query the database for the schema cache. Retrying."}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        })

        try {
            waitForPostgrestSchemaCacheReady(
                httpClient = client,
                selfHostedUrl = "https://supabase.suprclaw.com/",
                serviceKey = "svc-key",
                projectRef = "proj_abc12345",
                maxAttempts = 3,
                delayMillis = 0
            )
        } finally {
            client.close()
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `waitForPostgrestSchemaCacheReady fails fast on non transient response`() = runTest {
        var attempts = 0
        val client = HttpClient(MockEngine {
            attempts += 1
            respond(
                content = """{"code":"PGRST301","message":"Invalid JWT"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })

        try {
            val error = assertFailsWith<IllegalStateException> {
                waitForPostgrestSchemaCacheReady(
                    httpClient = client,
                    selfHostedUrl = "https://supabase.suprclaw.com",
                    serviceKey = "svc-key",
                    projectRef = "proj_abc12345",
                    maxAttempts = 3,
                    delayMillis = 0
                )
            }

            assertTrue(error.message!!.contains("status=401"))
            assertTrue(error.message!!.contains("PGRST301"))
        } finally {
            client.close()
        }

        assertEquals(1, attempts)
    }
}
