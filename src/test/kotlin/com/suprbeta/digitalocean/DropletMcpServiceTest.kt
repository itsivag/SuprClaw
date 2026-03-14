package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.supabase.SupabaseManagementService
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DropletMcpServiceTest {

    private val sshExecutor = mockk<SshCommandExecutor>()
    private val managementService = mockk<SupabaseManagementService>(relaxed = true)

    private val podmanDroplet = UserDropletInternal(
        userId = "user1",
        dropletId = 1L,
        dropletName = "71bb0ef6c173d2db26c6f011f0d2743908f5891a3708def3ea255edbe124c7a8",
        deploymentMode = "podman",
        ipAddress = "1.2.3.4",
        supabaseProjectRef = "proj_abc12345",
        gatewayToken = "gw-token"
    )

    private fun buildService(application: Application, testEnv: Map<String, String> = emptyMap()): DropletMcpServiceImpl =
        object : DropletMcpServiceImpl(managementService, sshExecutor, application) {
            override fun env(key: String): String = testEnv[key] ?: ""
        }

    @Test
    fun `validateMcpTools rejects missing provider secret`() = testApplication {
        val service = buildService(application)

        val error = assertFailsWith<IllegalStateException> {
            service.validateMcpTools(listOf("zapier"))
        }

        assertContains(error.message.orEmpty(), "ZAPIER_MCP_EMBED_SECRET")
    }

    @Test
    fun `configureMcpTools writes PicoClaw MCP config and restarts gateway`() = testApplication {
        val commands = mutableListOf<String>()
        every { sshExecutor.runSshCommand(any(), any()) } answers {
            commands += secondArg<String>()
            ""
        }
        every { managementService.managementToken } returns "mgmt-token"
        val service = buildService(application)

        service.configureMcpTools(podmanDroplet, listOf("supabase", "cloud_browser"))

        assertEquals(2, commands.size)
        assertContains(commands[0], "podman exec")
        assertContains(commands[0], "picoclaw.json")
        assertContains(commands[0], "\"https://supabase.suprclaw.com\"")
        assertContains(commands[0], "\"https://api.suprclaw.com/api/mcp/cloud-browser\"")
        assertContains(commands[0], "mgmt-token")
        assertContains(commands[0], "gw-token")
        assertContains(commands[1], "supervisorctl restart picoclaw-gateway")
    }
}
