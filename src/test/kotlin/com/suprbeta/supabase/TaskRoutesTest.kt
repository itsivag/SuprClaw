package com.suprbeta.supabase

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.supabase.models.CreateTaskRequest
import com.suprbeta.supabase.models.DeleteTaskResponse
import com.suprbeta.supabase.models.DeliverableListResponse
import com.suprbeta.supabase.models.Task
import com.suprbeta.supabase.models.TaskDocument
import com.suprbeta.supabase.models.UpdateTaskRequest
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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

    @Test
    fun `create task endpoint returns created task`() = testApplication {
        application { configureTestModule() }

        val request = CreateTaskRequest(title = "Ship v1", description = "Prepare release", status = "inbox", priority = 2)
        val createdTask = Task(id = "task-1", title = "Ship v1", description = "Prepare release", status = "inbox", priority = 2)

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.createTask(supabaseClient, request) } returns createdTask

        val response = client.post("/api/tasks") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateTaskRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.decodeFromString<Task>(response.bodyAsText())
        assertEquals("task-1", body.id)
        assertEquals("Ship v1", body.title)
        coVerify(exactly = 1) { taskRepository.createTask(supabaseClient, request) }
    }

    @Test
    fun `create task endpoint rejects blank title`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)

        val response = client.post("/api/tasks") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"","status":"inbox","priority":5}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { firestoreRepository.getUserDropletInternal(any()) }
        coVerify(exactly = 0) { taskRepository.createTask(any(), any()) }
    }

    @Test
    fun `update task endpoint returns updated task`() = testApplication {
        application { configureTestModule() }

        val request = UpdateTaskRequest(title = "Ship v1", description = "Ready for review", status = "review", priority = 1)
        val updatedTask = Task(id = "task-1", title = "Ship v1", description = "Ready for review", status = "review", priority = 1)

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.updateTask(supabaseClient, "task-1", request) } returns updatedTask

        val response = client.put("/api/tasks/task-1") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateTaskRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<Task>(response.bodyAsText())
        assertEquals("review", body.status)
        coVerify(exactly = 1) { taskRepository.updateTask(supabaseClient, "task-1", request) }
    }

    @Test
    fun `update task endpoint returns 404 when task is missing`() = testApplication {
        application { configureTestModule() }

        val request = UpdateTaskRequest(title = "Ship v1", description = null, status = "done", priority = 1)

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.updateTask(supabaseClient, "missing-task", request) } returns null

        val response = client.put("/api/tasks/missing-task") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateTaskRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete task endpoint returns deleted task id`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.deleteTask(supabaseClient, "task-1") } returns Task(id = "task-1", title = "Ship v1")

        val response = client.delete("/api/tasks/task-1") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<DeleteTaskResponse>(response.bodyAsText())
        assertEquals("task-1", body.id)
        assertEquals("Task deleted", body.message)
        coVerify(exactly = 1) { taskRepository.deleteTask(supabaseClient, "task-1") }
    }

    @Test
    fun `delete task endpoint returns 404 when task is missing`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-token") } returns FirebaseUser("user-1", "user@example.com", true)
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        coEvery { taskRepository.deleteTask(supabaseClient, "missing-task") } returns null

        val response = client.delete("/api/tasks/missing-task") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
