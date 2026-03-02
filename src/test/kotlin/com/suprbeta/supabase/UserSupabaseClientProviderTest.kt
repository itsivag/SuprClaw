package com.suprbeta.supabase

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Tests for [UserSupabaseClientProvider] schema-based client caching.
 *
 * Each unique (url, schema) pair must return the same [SupabaseClient] instance on every call
 * so that the Postgrest plugin's `defaultSchema` is bound correctly and we don't leak connections.
 */
class UserSupabaseClientProviderTest {

    // A fresh provider for each test to keep caches isolated.
    private fun provider() = UserSupabaseClientProvider()

    @Test
    fun `same url and schema returns same client instance`() {
        val p = provider()
        val c1 = p.getClient("https://fake.supabase.co", "key1", "public")
        val c2 = p.getClient("https://fake.supabase.co", "key1", "public")
        assertSame(c1, c2, "Expected cached client for identical (url, schema)")
    }

    @Test
    fun `different schema returns different client instance`() {
        val p = provider()
        val c1 = p.getClient("https://fake.supabase.co", "key1", "public")
        val c2 = p.getClient("https://fake.supabase.co", "key1", "proj_abc")
        assertNotSame(c1, c2, "Different schema must produce a distinct client with its own defaultSchema")
    }

    @Test
    fun `different url returns different client instance`() {
        val p = provider()
        val c1 = p.getClient("https://fake1.supabase.co", "key1", "public")
        val c2 = p.getClient("https://fake2.supabase.co", "key1", "public")
        assertNotSame(c1, c2, "Different URL must produce a distinct client")
    }

    @Test
    fun `default schema is public`() {
        val p = provider()
        val withDefault = p.getClient("https://fake.supabase.co", "key1")
        val withExplicit = p.getClient("https://fake.supabase.co", "key1", "public")
        assertSame(withDefault, withExplicit, "Omitting schema should use public and hit the same cache entry")
    }

    @Test
    fun `multiple calls with self-hosted schema return same instance`() {
        val p = provider()
        val c1 = p.getClient("https://supabase.suprclaw.com", "svc_key", "proj_abc12345")
        val c2 = p.getClient("https://supabase.suprclaw.com", "svc_key", "proj_abc12345")
        assertSame(c1, c2)
    }

    @Test
    fun `two different self-hosted schemas return different instances`() {
        val p = provider()
        val c1 = p.getClient("https://supabase.suprclaw.com", "svc_key", "proj_aaa11111")
        val c2 = p.getClient("https://supabase.suprclaw.com", "svc_key", "proj_bbb22222")
        assertNotSame(c1, c2)
    }
}
