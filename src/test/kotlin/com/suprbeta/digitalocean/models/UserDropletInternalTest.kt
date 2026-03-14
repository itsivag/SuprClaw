package com.suprbeta.digitalocean.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserDropletInternalTest {

    @Test
    fun `resolveSupabaseUrl returns stored url when non-blank`() {
        val droplet = UserDropletInternal(
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseProjectRef = "proj_abc12345"
        )
        assertEquals("https://supabase.suprclaw.com", droplet.resolveSupabaseUrl())
    }

    @Test
    fun `resolveSupabaseUrl derives url from projectRef when supabaseUrl is blank`() {
        val droplet = UserDropletInternal(
            supabaseUrl = "",
            supabaseProjectRef = "myhostedref"
        )
        assertEquals("https://myhostedref.supabase.co", droplet.resolveSupabaseUrl())
    }

    @Test
    fun `resolveSupabaseUrl uses stored url even when projectRef is also set`() {
        // Self-hosted: supabaseUrl is the shared instance URL, not derived from projectRef
        val droplet = UserDropletInternal(
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseProjectRef = "proj_abc12345"
        )
        // Must return the stored URL, not derive a .supabase.co URL
        assertTrue(droplet.resolveSupabaseUrl().startsWith("https://supabase.suprclaw.com"))
    }

    @Test
    fun `supabaseSchema defaults to public`() {
        val droplet = UserDropletInternal(supabaseProjectRef = "someref")
        assertEquals("public", droplet.supabaseSchema)
    }

    @Test
    fun `supabaseSchema stores custom schema for self-hosted`() {
        val droplet = UserDropletInternal(
            supabaseProjectRef = "proj_abc12345",
            supabaseSchema = "proj_abc12345"
        )
        assertEquals("proj_abc12345", droplet.supabaseSchema)
    }

    @Test
    fun `supabaseUrl defaults to blank`() {
        val droplet = UserDropletInternal(supabaseProjectRef = "ref")
        assertEquals("", droplet.supabaseUrl)
    }

    @Test
    fun `no-arg constructor default values are consistent`() {
        val droplet = UserDropletInternal()
        assertEquals("public", droplet.supabaseSchema)
        assertEquals("", droplet.supabaseUrl)
        assertEquals("", droplet.supabaseProjectRef)
        // resolveSupabaseUrl with both blank should not crash
        assertEquals("https://.supabase.co", droplet.resolveSupabaseUrl())
    }

    @Test
    fun `toUserDroplet strips sensitive fields`() {
        val internal = UserDropletInternal(
            userId = "user1",
            dropletId = 42L,
            dropletName = "my-server",
            gatewayUrl = "wss://api.suprclaw.com",
            vpsGatewayUrl = "https://secret.suprclaw.com",
            gatewayToken = "token123",
            sshKey = "ssh-key",
            ipAddress = "1.2.3.4",
            supabaseProjectRef = "proj_abc",
            supabaseServiceKey = "svc_key",
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseSchema = "proj_abc",
            createdAt = "2024-01-01T00:00:00Z",
            status = "active",
            sslEnabled = false
        )
        val public = internal.toUserDroplet()

        assertEquals("user1", public.userId)
        assertEquals(42L, public.dropletId)
        assertEquals("my-server", public.dropletName)
        assertEquals("wss://api.suprclaw.com", public.gatewayUrl)
        assertEquals("token123", public.gatewayToken)
        assertEquals("active", public.status)
        assertEquals("2024-01-01T00:00:00Z", public.createdAt)
    }
}
