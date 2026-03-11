package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceService
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Base64

class AgentWorkspaceServiceTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val agentRepository = mockk<SupabaseAgentRepository>()
    private val userClientProvider = mockk<UserSupabaseClientProvider>()
    private val sshExecutor = mockk<SshCommandExecutor>()
    private val supabaseClient = mockk<SupabaseClient>(relaxed = true)

    private val dockerDroplet = UserDropletInternal(
        userId = "user-1",
        dropletId = 99L,
        dropletName = "71bb0ef6c173d2db26c6f011f0d2743908f5891a3708def3ea255edbe124c7a8",
        ipAddress = "10.0.0.5",
        status = "active",
        supabaseUrl = "https://supabase.suprclaw.com",
        supabaseServiceKey = "service-key",
        supabaseSchema = "proj_abc123",
        deploymentMode = "docker"
    )

    private val vpsDroplet = dockerDroplet.copy(
        dropletName = "openclaw-vps",
        deploymentMode = "vps"
    )

    private val catalogJson = """
        {
          "repo": "https://github.com/itsivag/SuprClaw.git",
          "agents": [
            {
              "id": "content-writer",
              "name": "Content Writer",
              "description": "Writes content",
              "session_key": "agent:content-writer:main",
              "is_lead": false,
              "capabilities": [],
              "best_with": [],
              "source_path": "marketplace/content",
              "install_path": ".openclaw/workspace-content",
              "mcp_tools": []
            }
          ]
        }
    """.trimIndent()

    private fun buildMarketplaceService(): MarketplaceService {
        val engine = MockEngine { _ ->
            respond(
                content = catalogJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return MarketplaceService(HttpClient(engine), catalogUrl = "https://example.test/agents.json")
    }

    private fun buildService(application: Application): AgentWorkspaceServiceImpl =
        AgentWorkspaceServiceImpl(
            firestoreRepository = firestoreRepository,
            agentRepository = agentRepository,
            userClientProvider = userClientProvider,
            marketplaceService = buildMarketplaceService(),
            sshCommandExecutor = sshExecutor,
            application = application
        )

    @Test
    fun `listWorkspaceFiles resolves Lead to the lead workspace path in docker mode`() = testApplication {
        val commandSlot = slot<String>()
        val service = buildService(application)

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { sshExecutor.runSshCommand("10.0.0.5", capture(commandSlot)) } returns "SOUL.md\nUSER.md\n"

        val response = service.listWorkspaceFiles("user-1", 99L, "Lead")

        assertEquals(listOf("SOUL.md", "USER.md"), response.files)
        assertEquals("lead", response.workspaceType)
        assertTrue(commandSlot.captured.contains("docker exec"))
        assertTrue(commandSlot.captured.contains("71bb0ef6c173d2db26c6f011f0d2743908f5891a3708def3ea255edbe124c7a8"))
        assertTrue(commandSlot.captured.contains("/home/openclaw/.openclaw/workspace"))
        assertFalse(commandSlot.captured.contains("AGENTS.md"))
        assertFalse(commandSlot.captured.contains("TOOLS.md"))
        assertFalse(commandSlot.captured.contains("HEARTBEAT.md"))
        assertFalse(commandSlot.captured.contains("BOOTSTRAP.md"))
    }

    @Test
    fun `listWorkspaceFiles resolves marketplace agents through installPath`() = testApplication {
        val commandSlot = slot<String>()
        val service = buildService(application)

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { agentRepository.getAgents(supabaseClient) } returns listOf(
            com.suprbeta.digitalocean.models.UserAgent(name = "content-writer")
        )
        every { sshExecutor.runSshCommand("10.0.0.5", capture(commandSlot)) } returns "IDENTITY.md\n"

        val response = service.listWorkspaceFiles("user-1", 99L, "content-writer")

        assertEquals("marketplace", response.workspaceType)
        assertEquals(listOf("IDENTITY.md"), response.files)
        assertTrue(commandSlot.captured.contains("docker exec"))
        assertTrue(commandSlot.captured.contains("/home/openclaw/.openclaw/workspace-content"))
    }

    @Test
    fun `listWorkspaceFiles filters hidden workspace docs from ssh output`() = testApplication {
        val service = buildService(application)

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { sshExecutor.runSshCommand("10.0.0.5", any()) } returns
            "AGENTS.md\nTOOLS.md\nHEARTBEAT.md\nBOOTSTRAP.md\nSOUL.md\nUSER.md\n"

        val response = service.listWorkspaceFiles("user-1", 99L, "Lead")

        assertEquals(listOf("SOUL.md", "USER.md"), response.files)
    }

    @Test
    fun `getWorkspaceFile decodes base64 content in docker mode`() = testApplication {
        val commandSlot = slot<String>()
        val service = buildService(application)
        val encoded = Base64.getEncoder().encodeToString("# AGENTS.md\n\nHello".toByteArray())

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { sshExecutor.runSshCommand("10.0.0.5", capture(commandSlot)) } returns encoded

        val response = service.getWorkspaceFile("user-1", 99L, "Lead", "AGENTS.md")

        assertEquals("AGENTS.md", response.fileName)
        assertEquals("# AGENTS.md\n\nHello", response.content)
        assertTrue(commandSlot.captured.contains("base64 -w0"))
        assertTrue(commandSlot.captured.contains("/home/openclaw/.openclaw/workspace/AGENTS.md"))
        assertTrue(commandSlot.captured.contains("docker exec"))
    }

    @Test
    fun `getWorkspaceFile rejects invalid filenames before ssh`() = testApplication {
        val service = buildService(application)
        coEvery { firestoreRepository.getUserDropletInternal(any()) } returns dockerDroplet

        assertFailsWith<IllegalArgumentException> {
            service.getWorkspaceFile("user-1", 99L, "Lead", "../../secret.txt")
        }

        verify(exactly = 0) { sshExecutor.runSshCommand(any(), any()) }
    }

    @Test
    fun `listWorkspaceFiles returns not found when marketplace agent is not installed`() = testApplication {
        val service = buildService(application)

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns dockerDroplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { agentRepository.getAgents(supabaseClient) } returns emptyList()

        assertFailsWith<NoSuchElementException> {
            service.listWorkspaceFiles("user-1", 99L, "content-writer")
        }

        verify(exactly = 0) { sshExecutor.runSshCommand(any(), any()) }
    }

    @Test
    fun `getWorkspaceFile uses direct host commands for VPS fallback`() = testApplication {
        val commandSlot = slot<String>()
        val service = buildService(application)
        val encoded = Base64.getEncoder().encodeToString("Lead body".toByteArray())

        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns vpsDroplet
        every { sshExecutor.runSshCommand("10.0.0.5", capture(commandSlot)) } returns encoded

        val response = service.getWorkspaceFile("user-1", 99L, "Lead", "AGENTS.md")

        assertEquals("Lead body", response.content)
        assertFalse(commandSlot.captured.contains("docker exec"))
        assertTrue(commandSlot.captured.contains("base64 -w0 '/home/openclaw/.openclaw/workspace/AGENTS.md'"))
    }
}
