package com.suprbeta.supabase

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.statement.HttpResponse
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseAgentRepositoryTest {

    @Test
    fun `retryTransientPostgrestSchemaCacheErrors retries transient schema cache failures`() = runTest {
        var attempts = 0

        val result = retryTransientPostgrestSchemaCacheErrors(
            maxAttempts = 3,
            initialDelayMillis = 0
        ) {
            attempts += 1
            if (attempts < 3) throw postgrestError("PGRST002")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retryTransientPostgrestSchemaCacheErrors does not retry non transient failures`() = runTest {
        var attempts = 0

        assertFailsWith<PostgrestRestException> {
            retryTransientPostgrestSchemaCacheErrors(
                maxAttempts = 3,
                initialDelayMillis = 0
            ) {
                attempts += 1
                throw postgrestError("PGRST301")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `isTransientPostgrestSchemaCacheError matches supported PostgREST cache codes`() {
        assertTrue(isTransientPostgrestSchemaCacheError(postgrestError("PGRST002")))
        assertTrue(isTransientPostgrestSchemaCacheError(postgrestError("PGRST205")))
        assertFalse(isTransientPostgrestSchemaCacheError(postgrestError("PGRST301")))
        assertFalse(isTransientPostgrestSchemaCacheError(IllegalStateException("nope")))
    }

    private fun postgrestError(code: String): PostgrestRestException =
        PostgrestRestException(
            "error",
            "",
            JsonNull,
            code,
            mockk<HttpResponse>(relaxed = true)
        )
}
