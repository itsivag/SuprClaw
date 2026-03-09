package com.suprbeta.supabase

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.supabase.models.DeliverableListResponse
import com.suprbeta.supabase.models.TaskDocument
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskRoutesTest {
    private val taskRepository = mockk<SupabaseTaskRepository>()
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val userClientProvider = mockk<UserSupabaseClientProvider>()
    private val authService = mockk<FirebaseAuthService>()
    private val supabaseClient = mockk<SupabaseClient>()
    private val json = Json { ignoreUnknownKeys = true }

    private val droplet = UserDropletInternal(
        userId = "user-1",
        dropletId = 99L,
        supabaseProjectRef = "project-1",
        supabaseServiceKey = "service-key",
        supabaseSchema = "public"
    )

    private fun Application.configureTestModule() {
        configureSerialization()
        install(FirebaseAuthPlugin) {
            authService = this@TaskRoutesTest.authService
        }
        configureTaskRoutes(taskRepository, firestoreRepository, userClientProvider)
    }

    @Test
    fun `deliverables endpoint returns count and rows`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.getDeliverables(supabaseClient) } returns listOf(
            TaskDocument(id = "doc-1", taskId = "task-1", title = "Deliverable 1", content = "Body 1", version = 1),
            TaskDocument(id = "doc-2", taskId = "task-1", title = "Deliverable 1", content = "Body 2", version = 2)
        )

        val response = client.get("/api/tasks/deliverables") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<DeliverableListResponse>(response.bodyAsText())
        assertEquals(2, body.count)
        assertEquals(2, body.deliverables.size)
        assertEquals("doc-1", body.deliverables.first().id)
    }

    @Test
    fun `deliverables endpoint returns 404 when user has no droplet`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns null

        val response = client.get("/api/tasks/deliverables") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        coVerify(exactly = 0) { taskRepository.getDeliverables(any()) }
    }

    @Test
    fun `deliverables endpoint returns 500 when repository fails`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.getDeliverables(supabaseClient) } throws IllegalStateException("supabase failure")

        val response = client.get("/api/tasks/deliverables") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("supabase failure"))
    }

    @Test
    fun `deliverables endpoint returns 401 when auth header is missing`() = testApplication {
        application { configureTestModule() }

        val response = client.get("/api/tasks/deliverables")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `deliverables endpoint returns 401 for invalid token`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("bad-token") } returns null

        val response = client.get("/api/tasks/deliverables") {
            header(HttpHeaders.Authorization, "Bearer bad-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `deliverables endpoint does not resolve to task detail route`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.getDeliverables(supabaseClient) } returns emptyList()

        val response = client.get("/api/tasks/deliverables") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { taskRepository.getDeliverables(supabaseClient) }
        coVerify(exactly = 0) { taskRepository.getTaskDetail(any(), any()) }
    }
}
