package com.suprbeta.admin

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.podman.models.HostInfo
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminMetricsServiceTest {
    @Test
    fun `collect metrics maps containers to users and aggregates host resources`() = testApplication {
        val firestoreRepository = mockk<FirestoreRepository>()
        val ssh = mockk<SshCommandExecutor>()
        lateinit var service: AdminMetricsService

        application {
            service = AdminMetricsService(firestoreRepository, ssh, this)
        }
        client.get("/")

        coEvery { firestoreRepository.listHosts() } returns listOf(
            HostInfo(hostId = 1L, hostIp = "10.0.0.1", status = HostInfo.STATUS_ACTIVE),
            HostInfo(hostId = 2L, hostIp = "10.0.0.2", status = HostInfo.STATUS_ACTIVE)
        )
        coEvery { firestoreRepository.listAllUserDropletsInternal() } returns listOf(
            UserDropletInternal(
                userId = "user-1",
                dropletId = 1L,
                dropletName = "abcdef1234567890",
                deploymentMode = "podman"
            ),
            UserDropletInternal(
                userId = "user-2",
                dropletId = 2L,
                dropletName = "2222222222222222",
                deploymentMode = "podman"
            )
        )

        every { ssh.runSshCommand(eq("10.0.0.1"), match { it.contains("podman stats --no-stream") }) } returns
            """{"ID":"abcdef123456","CPUPerc":"20.0%","MemUsage":"100MiB / 1GiB","NetIO":"1MB / 2MB"}"""
        every { ssh.runSshCommand(eq("10.0.0.2"), match { it.contains("podman stats --no-stream") }) } returns
            """{"ID":"222222222222","CPUPerc":"40.0%","MemUsage":"50MiB / 500MiB","NetIO":"500kB / 600kB"}"""

        every { ssh.runSshCommand(eq("10.0.0.1"), match { !it.contains("podman stats --no-stream") }) } returns
            """
            CPU_PERCENT=50
            MEM_TOTAL_BYTES=200
            MEM_AVAILABLE_BYTES=50
            NET_RX_BYTES=1000
            NET_TX_BYTES=2000
            CORE_COUNT=2
            """.trimIndent()
        every { ssh.runSshCommand(eq("10.0.0.2"), match { !it.contains("podman stats --no-stream") }) } returns
            """
            CPU_PERCENT=25
            MEM_TOTAL_BYTES=1000
            MEM_AVAILABLE_BYTES=500
            NET_RX_BYTES=4000
            NET_TX_BYTES=8000
            CORE_COUNT=6
            """.trimIndent()

        val response = service.collectMetrics()
        assertEquals(2, response.hosts.size)
        assertEquals(2, response.overall.containerCount)
        assertEquals(2, response.overall.userCount)
        assertEquals(31.25, response.overall.cpuPercent)
        assertEquals(650L, response.overall.memoryUsedBytes)
        assertEquals(1200L, response.overall.memoryTotalBytes)
        assertEquals(5000L, response.overall.networkRxBytes)
        assertEquals(10000L, response.overall.networkTxBytes)

        val host1 = response.hosts.first { it.hostId == 1L }
        val host2 = response.hosts.first { it.hostId == 2L }
        assertEquals("user-1", host1.containers.first().userId)
        assertEquals("user-2", host2.containers.first().userId)
    }

    @Test
    fun `collect metrics continues when podman stats fails on a host`() = testApplication {
        val firestoreRepository = mockk<FirestoreRepository>()
        val ssh = mockk<SshCommandExecutor>()
        lateinit var service: AdminMetricsService

        application {
            service = AdminMetricsService(firestoreRepository, ssh, this)
        }
        client.get("/")

        coEvery { firestoreRepository.listHosts() } returns listOf(
            HostInfo(hostId = 9L, hostIp = "10.0.0.9", status = HostInfo.STATUS_ACTIVE)
        )
        coEvery { firestoreRepository.listAllUserDropletsInternal() } returns listOf(
            UserDropletInternal(
                userId = "user-9",
                dropletId = 9L,
                dropletName = "aaaaabbbbbccccc",
                deploymentMode = "podman"
            )
        )

        every { ssh.runSshCommand(eq("10.0.0.9"), match { it.contains("podman stats --no-stream") }) } throws RuntimeException("stats failed")
        every { ssh.runSshCommand(eq("10.0.0.9"), match { !it.contains("podman stats --no-stream") }) } returns
            """
            CPU_PERCENT=30
            MEM_TOTAL_BYTES=1000
            MEM_AVAILABLE_BYTES=800
            NET_RX_BYTES=10
            NET_TX_BYTES=20
            CORE_COUNT=2
            """.trimIndent()

        val response = service.collectMetrics()
        assertEquals(1, response.hosts.size)
        val host = response.hosts.first()
        assertNotNull(host.error)
        assertTrue(host.error.contains("podman stats unavailable"))
        assertEquals(1, host.containers.size)
        assertEquals("missing", host.containers.first().status)
        assertEquals("user-9", host.containers.first().userId)
    }

    @Test
    fun `collect metrics returns empty structures when no active hosts`() = testApplication {
        val firestoreRepository = mockk<FirestoreRepository>()
        val ssh = mockk<SshCommandExecutor>()
        lateinit var service: AdminMetricsService

        application {
            service = AdminMetricsService(firestoreRepository, ssh, this)
        }
        client.get("/")

        coEvery { firestoreRepository.listHosts() } returns emptyList()
        coEvery { firestoreRepository.listAllUserDropletsInternal() } returns emptyList()

        val response = service.collectMetrics()
        assertEquals(0, response.overall.hostCount)
        assertEquals(0, response.overall.containerCount)
        assertEquals(0, response.hosts.size)
    }
}
