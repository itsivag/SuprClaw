package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.runtime.AgentRuntime
import com.suprbeta.runtime.AgentRuntimeRegistry
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
        runtimeRegistry = AgentRuntimeRegistry(),
        application = application
    )

    @Test
    fun `createAgent executes inside the tenant container for docker deployments`() = testApplication {
        val service = buildService(application)
        val commands = mutableListOf<String>()
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
        every { sshExecutor.runSshCommand("10.0.0.5", any()) } answers {
            commands += secondArg<String>()
            "created"
        }
        every { userClientProvider.getClient(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { agentRepository.saveAgent(any(), any()) } just Runs

        val result = service.createAgent(
            userId = "user-1",
            name = "writer",
            role = "Writes content",
            model = "anthropic/claude-3-7-sonnet"
        )

        assertEquals("created", result)
        assertTrue(commands.any { it.contains("docker exec 'abcdef1234567890'") })
        assertTrue(commands.any { it.contains("su - picoclaw -s /bin/sh -lc") })
        assertTrue(commands.any { it.contains("picoclaw.json") })
        assertTrue(commands.any { it.contains("/home/picoclaw/.picoclaw/workspace-writer/AGENTS.md") })
        assertTrue(commands.any { it.contains("SUPRCLAW_SKILL_BOOTSTRAP_V2") })
        assertTrue(commands.any { it.contains("/skills/suprclaw-cloud-browser/SKILL.md") })
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

    @Test
    fun `deleteAgent removes remote workspace without deleting user droplet`() = testApplication {
        val service = buildService(application)
        val commands = mutableListOf<String>()
        val userDroplet = UserDropletInternal(
            userId = "user-3",
            dropletId = 101L,
            dropletName = "droplet-3",
            ipAddress = "10.0.0.7",
            status = "active",
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseServiceKey = "service-key",
            supabaseSchema = "proj_user3"
        )
        val supabaseClient = mockk<io.github.jan.supabase.SupabaseClient>(relaxed = true)

        coEvery { firestoreRepository.getUserDropletInternal("user-3") } returns userDroplet
        every { sshExecutor.runSshCommandOnce("10.0.0.7", any()) } answers {
            commands += secondArg<String>()
            "deleted"
        }
        every { sshExecutor.runSshCommand("10.0.0.7", any()) } answers {
            commands += secondArg<String>()
            "workspace removed"
        }
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { agentRepository.deleteAgent(supabaseClient, "writer") } just Runs

        val result = service.deleteAgent("user-3", "writer")

        assertEquals("deleted", result)
        assertTrue(commands.any { it.contains("picoclaw.json") })
        assertTrue(commands.any { it.contains("rm -rf '/home/picoclaw/.picoclaw/workspace-writer'") })
        coVerify(exactly = 0) { firestoreRepository.deleteUserDroplet(any()) }
    }

    @Test
    fun `createAgent mutates picoclaw config for picoclaw tenants`() = testApplication {
        val service = buildService(application)
        val commands = mutableListOf<String>()
        val droplet = UserDropletInternal(
            userId = "user-4",
            dropletId = 102L,
            dropletName = "abcdef1234567890",
            ipAddress = "10.0.0.8",
            status = "active",
            supabaseUrl = "https://supabase.suprclaw.com",
            supabaseServiceKey = "service-key",
            supabaseSchema = "proj_user4",
            deploymentMode = "docker",
            agentRuntime = AgentRuntime.PICOCLAW.wireValue
        )

        coEvery { firestoreRepository.getUserDropletInternal("user-4") } returns droplet
        every { sshExecutor.runSshCommand("10.0.0.8", any()) } answers {
            commands += secondArg<String>()
            "ok"
        }
        every { userClientProvider.getClient(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { agentRepository.saveAgent(any(), any()) } just Runs

        service.createAgent("user-4", "writer", "Writes content", null)

        assertTrue(commands.any { it.contains("picoclaw.json") })
        assertTrue(commands.any { it.contains("picoclaw agent") }.not())
        assertTrue(commands.any { it.contains("supervisorctl restart picoclaw-gateway") })
    }
}
