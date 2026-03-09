package com.suprbeta.admin

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.DropletProvisioningService
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseUser
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.usage.DailyUsageData
import io.ktor.client.request.delete
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
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRoutesTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val provisioningService = mockk<DropletProvisioningService>(relaxed = true)
    private val metricsService = mockk<AdminMetricsService>()
    private val authService = mockk<FirebaseAuthService>()
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUser = FirebaseUser(
        uid = "admin-user",
        email = "admin@example.com",
        emailVerified = true,
        customClaims = mapOf("role" to "admin")
    )

    private fun Application.configureTestModule() {
        configureSerialization()
        install(FirebaseAuthPlugin) {
            authService = this@AdminRoutesTest.authService
        }
        configureAdminRoutes(firestoreRepository, provisioningService, metricsService)
    }

    @Test
    fun `admin can list users with scope all`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-admin-token") } returns adminUser
        coEvery { firestoreRepository.listAllUserDropletsInternal() } returns listOf(
            UserDropletInternal(
                userId = "user-1",
                dropletId = 101L,
                deploymentMode = "docker",
                status = "active",
                vpsGatewayUrl = "https://user-1.suprclaw.com"
            )
        )
        coEvery { firestoreRepository.listUserIds() } returns listOf("user-1", "user-2")
        coEvery { firestoreRepository.getDailyUsageDetail(any(), any()) } returns DailyUsageData()

        val response = client.get("/api/admin/users?scope=all") {
            header(HttpHeaders.Authorization, "Bearer good-admin-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<AdminUsersResponse>(response.bodyAsText())
        assertEquals(2, payload.count)

        val user1 = payload.users.first { it.userId == "user-1" }
        val user2 = payload.users.first { it.userId == "user-2" }
        assertTrue(user1.hasContainer)
        assertTrue(user1.canDelete)
        assertEquals("https://user-1.suprclaw.com", user1.containerUrl)
        assertFalse(user2.hasContainer)
        assertFalse(user2.canDelete)
    }

    @Test
    fun `non admin user receives forbidden`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("member-token") } returns FirebaseUser(
            uid = "member",
            email = "member@example.com",
            emailVerified = true,
            customClaims = mapOf("role" to "member")
        )

        val response = client.get("/api/admin/users") {
            header(HttpHeaders.Authorization, "Bearer member-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `admin can fetch live metrics`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-admin-token") } returns adminUser
        coEvery { metricsService.collectMetrics() } returns AdminMetricsResponse(
            capturedAtUtc = "2026-03-09T00:00:00Z",
            intervalSeconds = 10,
            overall = AdminOverallMetrics(
                hostCount = 1,
                containerCount = 1,
                userCount = 1,
                cpuPercent = 12.3,
                memoryUsedBytes = 100,
                memoryTotalBytes = 200,
                memoryUsagePercent = 50.0,
                networkRxBytes = 1024,
                networkTxBytes = 2048
            ),
            hosts = listOf(
                AdminHostMetrics(
                    hostId = 77L,
                    hostIp = "10.0.0.1",
                    hostStatus = "active",
                    cpuPercent = 12.3,
                    memoryUsedBytes = 100,
                    memoryTotalBytes = 200,
                    memoryUsagePercent = 50.0,
                    networkRxBytes = 1024,
                    networkTxBytes = 2048,
                    containers = listOf(
                        AdminContainerMetrics(
                            userId = "user-1",
                            containerId = "abcdef123456",
                            status = "running",
                            cpuPercent = 1.0,
                            memoryUsedBytes = 20,
                            memoryLimitBytes = 100,
                            networkRxBytes = 10,
                            networkTxBytes = 11
                        )
                    )
                )
            )
        )

        val response = client.get("/api/admin/metrics") {
            header(HttpHeaders.Authorization, "Bearer good-admin-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AdminMetricsResponse>(response.bodyAsText())
        assertEquals(10, body.intervalSeconds)
        assertEquals(1, body.hosts.size)
        assertEquals("user-1", body.hosts.first().containers.first().userId)
    }

    @Test
    fun `non admin user cannot fetch metrics`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("member-token") } returns FirebaseUser(
            uid = "member",
            email = "member@example.com",
            emailVerified = true,
            customClaims = mapOf("role" to "member")
        )

        val response = client.get("/api/admin/metrics") {
            header(HttpHeaders.Authorization, "Bearer member-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `delete user returns conflict when user has no container`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-admin-token") } returns adminUser
        coEvery { firestoreRepository.getUserDropletInternal("user-no-container") } returns null

        val response = client.delete("/api/admin/users/user-no-container") {
            header(HttpHeaders.Authorization, "Bearer good-admin-token")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        coVerify(exactly = 0) { provisioningService.teardown(any()) }
    }

    @Test
    fun `delete user succeeds when container exists`() = testApplication {
        application { configureTestModule() }

        coEvery { authService.verifyToken("good-admin-token") } returns adminUser
        coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns UserDropletInternal(
            userId = "user-1",
            dropletId = 42L
        )

        val response = client.delete("/api/admin/users/user-1") {
            header(HttpHeaders.Authorization, "Bearer good-admin-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { provisioningService.teardown("user-1") }
    }
}
