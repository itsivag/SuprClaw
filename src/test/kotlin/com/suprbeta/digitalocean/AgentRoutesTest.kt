package com.suprbeta.digitalocean

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.models.AgentFileContentResponse
import com.suprbeta.digitalocean.models.AgentFileListResponse
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRoutesTest {
    private val configuringService = mockk<DropletConfigurationService>(relaxed = true)
    private val workspaceService = mockk<AgentWorkspaceService>()
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val agentRepository = mockk<SupabaseAgentRepository>(relaxed = true)
    private val userClientProvider = mockk<UserSupabaseClientProvider>(relaxed = true)
    private val authService = mockk<FirebaseAuthService>()
    private val json = Json { ignoreUnknownKeys = true }

    private val droplet = UserDropletInternal(
        userId = "user-1",
        dropletId = 99L,
        ipAddress = "10.0.0.5",
        status = "active"
    )

    private fun Application.configureTestModule() {
        configureSerialization()
        install(FirebaseAuthPlugin) {
            authService = this@AgentRoutesTest.authService
        }
        configureAgentRoutes(
            configuringService = configuringService,
            workspaceService = workspaceService,
            firestoreRepository = firestoreRepository,
            agentRepository = agentRepository,
            userClientProvider = userClientProvider
        )
    }

    @Test
    fun `owner can list files for Lead`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        coEvery { workspaceService.listWorkspaceFiles("user-1", 99L, "Lead") } returns AgentFileListResponse(
            dropletId = 99L,
            agentName = "Lead",
            workspaceType = "lead",
            files = listOf("SOUL.md", "USER.md")
        )

        val response = client.get("/api/agents/99/Lead/files") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AgentFileListResponse>(response.bodyAsText())
        assertEquals(listOf("SOUL.md", "USER.md"), body.files)
        assertEquals("lead", body.workspaceType)
    }

    @Test
    fun `owner can fetch AGENTS md for content writer`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        coEvery {
            workspaceService.getWorkspaceFile("user-1", 99L, "content-writer", "AGENTS.md")
        } returns AgentFileContentResponse(
            dropletId = 99L,
            agentName = "content-writer",
            workspaceType = "marketplace",
            fileName = "AGENTS.md",
            content = "# AGENTS.md\n\nHello"
        )

        val response = client.get("/api/agents/99/content-writer/files/AGENTS.md") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AgentFileContentResponse>(response.bodyAsText())
        assertEquals("AGENTS.md", body.fileName)
        assertTrue(body.content.contains("Hello"))
    }

    @Test
    fun `wrong droplet id returns forbidden`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet

        val response = client.get("/api/agents/100/Lead/files") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        coVerify(exactly = 0) { workspaceService.listWorkspaceFiles(any(), any(), any()) }
    }

    @Test
    fun `missing file returns not found`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        coEvery {
            workspaceService.getWorkspaceFile("user-1", 99L, "content-writer", "AGENTS.md")
        } throws NoSuchElementException("Workspace file not found")

        val response = client.get("/api/agents/99/content-writer/files/AGENTS.md") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `invalid file name returns bad request`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        coEvery {
            workspaceService.getWorkspaceFile("user-1", 99L, "content-writer", "SECRET.md")
        } throws IllegalArgumentException("Invalid file name")

        val response = client.get("/api/agents/99/content-writer/files/SECRET.md") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
