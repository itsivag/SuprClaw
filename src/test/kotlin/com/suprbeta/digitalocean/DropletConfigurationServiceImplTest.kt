package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DropletConfigurationServiceImplTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val agentRepository = mockk<SupabaseAgentRepository>()
    private val userClientProvider = mockk<UserSupabaseClientProvider>()
    private val sshExecutor = mockk<SshCommandExecutor>()
    private val dropletMcpService = mockk<DropletMcpService>(relaxed = true)

    private fun buildService(application: Application) = DropletConfigurationServiceImpl(
        firestoreRepository = firestoreRepository,
        agentRepository = agentRepository,
        userClientProvider = userClientProvider,
        sshCommandExecutor = sshExecutor,
        dropletMcpService = dropletMcpService,
        application = application
    )

    @Test
    fun `createAgent executes inside the tenant container for docker deployments`() = testApplication {
        val service = buildService(application)
        val commandSlot = slot<String>()
        val dockerDroplet = UserDropletInternal(
            userId = "user-1",
            dropletId = 99L,
            dropletName = "abcdef1234567890",
            ipAddress = "10.0.0.5",
            status = "active",
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseServiceKey = "service-key",
            supabaseSchema = "proj_abc123",
            deploymentMode = "docker"
        )

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { sshExecutor.runSshCommand("10.0.0.5", capture(commandSlot)) } returns "created"
        every { userClientProvider.getClient(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { agentRepository.saveAgent(any(), any()) } just Runs

        val result = service.createAgent(
            userId = "user-1",
            name = "writer",
            role = "Writes content",
            model = "anthropic/claude-3-7-sonnet"
        )

        assertEquals("created", result)
        assertTrue(commandSlot.captured.contains("docker exec 'abcdef1234567890'"))
        assertTrue(commandSlot.captured.contains("su - openclaw -s /bin/sh -lc"))
        assertTrue(commandSlot.captured.contains("openclaw agents add"))
    }

    @Test
    fun `createAgent rejects unsafe model values before running ssh`() = testApplication {
        val service = buildService(application)
        val dockerDroplet = UserDropletInternal(
            userId = "user-2",
            dropletId = 100L,
            dropletName = "abcdef1234567890",
            ipAddress = "10.0.0.6",
            status = "active",
            deploymentMode = "docker"
        )

        coEvery { firestoreRepository.getUserDropletInternal("user-2") } returns dockerDroplet

        assertFailsWith<IllegalArgumentException> {
            service.createAgent(
                userId = "user-2",
                name = "writer",
                role = "Writes content",
                model = "anthropic/claude; rm -rf /"
            )
        }

        verify(exactly = 0) { sshExecutor.runSshCommand(any(), any()) }
        coVerify(exactly = 0) { agentRepository.saveAgent(any(), any()) }
    }
}
