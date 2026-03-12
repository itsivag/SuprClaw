package com.suprbeta.digitalocean

import com.suprbeta.config.AppConfig
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.provider.DnsProvider
import com.suprbeta.provider.VpsService
import com.suprbeta.supabase.ProjectResult
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SupabaseManagementService
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import com.suprbeta.websocket.OpenClawConnector
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for [DropletProvisioningServiceImpl].
 *
 * Prerequisites:
 *  - SSL_ENABLED=false must be set in .env (or the environment) so the nginx/SSL
 *    phase is skipped and no disk reads of /etc/letsencrypt are attempted.
 */
class DropletProvisioningServiceImplTest {

    // ── AppConfig mock (SSL disabled so no cert file reads in test environment) ──

    @BeforeEach
    fun mockSslDisabled() {
        mockkObject(AppConfig)
        every { AppConfig.sslEnabled } returns false
    }

    @AfterEach
    fun unmockAppConfig() {
        unmockkObject(AppConfig)
    }

    // ── Shared mocks ──────────────────────────────────────────────────────────

    private val vpsService = mockk<VpsService>()
    private val dnsProvider = mockk<DnsProvider>()
    private val firestoreRepository = mockk<FirestoreRepository>(relaxed = true)
    private val agentRepository = mockk<SupabaseAgentRepository>(relaxed = true)
    private val schemaRepository = mockk<SupabaseSchemaRepository>(relaxed = true)
    private val managementService = mockk<SupabaseManagementService>()
    private val userClientProvider = mockk<UserSupabaseClientProvider>()
    private val openClawConnector = mockk<OpenClawConnector>()
    private val sshExecutor = mockk<SshCommandExecutor>()
    private val mcpService = mockk<DropletMcpService>()

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `createAndProvision returns correct initial status and non-blank password`() = testApplication {
        val service = buildService(application)
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(99L)

        val result = service.createAndProvision("my-server", "user-create")

        assertEquals(99L, result.dropletId)
        assertEquals(ProvisioningStatus.PHASE_CREATING, result.status.phase)
        assertEquals("my-server", result.status.droplet_name)
        assertTrue(result.password.isNotBlank(), "password should be non-blank")
    }

    @Test
    fun `createAndProvision sanitizes server name`() = testApplication {
        val service = buildService(application)
        val nameSlot = slot<String>()
        coEvery { vpsService.createServer(capture(nameSlot), any()) } returns VpsService.ServerCreateResult(1L)

        service.createAndProvision("My Server Name!", "user-sanitize")

        assertEquals("my-server-name-", nameSlot.captured)
    }

    @Test
    fun `getStatus returns null for unknown droplet`() = testApplication {
        val service = buildService(application)
        assertNull(service.getStatus(9999L))
    }

    @Test
    fun `getStatus reflects phase after createAndProvision`() = testApplication {
        val service = buildService(application)
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(42L)

        service.createAndProvision("server", "user-status")

        assertEquals(ProvisioningStatus.PHASE_CREATING, service.getStatus(42L)?.phase)
    }

    @Test
    fun `getStatusForUser only returns status for the owning user`() = testApplication {
        val service = buildService(application)
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(43L)

        service.createAndProvision("owned-server", "owner-user")

        assertNotNull(service.getStatusForUser(43L, "owner-user"))
        assertNull(service.getStatusForUser(43L, "other-user"))
    }

    @Test
    fun `createAndProvision rejects duplicate in flight requests for the same user`() = testApplication {
        val service = buildService(application)
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(44L)

        service.createAndProvision("first-server", "duplicate-user")

        assertFailsWith<IllegalStateException> {
            service.createAndProvision("second-server", "duplicate-user")
        }

        coVerify(exactly = 1) { vpsService.createServer(any(), any()) }
    }

    @Test
    fun `provisionDroplet stores supabaseUrl and supabaseSchema on UserDropletInternal`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_abc12345",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_abc12345"
        )

        val (dropletId, _, password) = service.createAndProvision("test-server", "user1")
        service.provisionDroplet(dropletId, password, "user1")

        val saved = captureLastSavedDroplet()
        assertEquals("https://supabase.suprclaw.com", saved.supabaseUrl)
        assertEquals("proj_abc12345", saved.supabaseSchema)
        assertEquals("proj_abc12345", saved.supabaseProjectRef)
        assertEquals("service-key", saved.supabaseServiceKey)
    }

    @Test
    fun `provisionDroplet stores public schema for hosted mode`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "hostedref123",
            endpoint = "https://hostedref123.supabase.co",
            schema = "public"
        )

        val (dropletId, _, password) = service.createAndProvision("hosted-server", "user2")
        service.provisionDroplet(dropletId, password, "user2")

        val saved = captureLastSavedDroplet()
        assertEquals("https://hostedref123.supabase.co", saved.supabaseUrl)
        assertEquals("public", saved.supabaseSchema)
    }

    @Test
    fun `provisionDroplet calls getClient with correct schema`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_xyz99",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_xyz99"
        )

        val (dropletId, _, password) = service.createAndProvision("schema-test", "user3")
        service.provisionDroplet(dropletId, password, "user3")

        verify {
            userClientProvider.getClient(
                "https://supabase.suprclaw.com",
                "service-key",
                "proj_xyz99"
            )
        }
    }

    @Test
    fun `provisionDroplet calls resolveSchema with the projectRef returned by createProject`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_resolvetest",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_resolvetest"
        )

        val (dropletId, _, password) = service.createAndProvision("resolve-test", "user4")
        service.provisionDroplet(dropletId, password, "user4")

        verify { managementService.resolveSchema("proj_resolvetest") }
    }

    @Test
    fun `provisionDroplet marks status PHASE_COMPLETE on success`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_complete",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_complete"
        )

        val (dropletId, _, password) = service.createAndProvision("complete-test", "user5")
        service.provisionDroplet(dropletId, password, "user5")

        assertEquals(ProvisioningStatus.PHASE_COMPLETE, service.getStatus(dropletId)?.phase)
    }

    @Test
    fun `provisionDroplet returns client-safe UserDroplet`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_safe",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_safe"
        )

        val (dropletId, _, password) = service.createAndProvision("safe-test", "user6")
        val result = service.provisionDroplet(dropletId, password, "user6")

        // Client-safe: no sensitive fields
        assertNotNull(result.gatewayToken)
        assertNotNull(result.userId)
    }

    @Test
    fun `provisionDroplet deletes VPS and Supabase project when SSH fails`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_fail",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_fail"
        )
        // SSH config step throws after project was created
        every { sshExecutor.runSshCommand(any(), any()) } throws RuntimeException("SSH failure")
        coEvery { vpsService.deleteServer(any()) } just Runs
        coEvery { managementService.deleteProject(any()) } just Runs

        val (dropletId, _, password) = service.createAndProvision("fail-test", "user7")
        assertFailsWith<Exception> {
            service.provisionDroplet(dropletId, password, "user7")
        }

        coVerify { vpsService.deleteServer(1L) }
        coVerify { managementService.deleteProject("proj_fail") }
    }

    @Test
    fun `provisionDroplet marks status PHASE_FAILED on error`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_phfail",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_phfail"
        )
        every { sshExecutor.runSshCommand(any(), any()) } throws RuntimeException("oops")
        coEvery { vpsService.deleteServer(any()) } just Runs
        coEvery { managementService.deleteProject(any()) } just Runs

        val (dropletId, _, password) = service.createAndProvision("phase-fail", "user8")
        runCatching { service.provisionDroplet(dropletId, password, "user8") }

        assertEquals(ProvisioningStatus.PHASE_FAILED, service.getStatus(dropletId)?.phase)
    }

    @Test
    fun `provisionDroplet does not call deleteProject when createProject itself fails`() = testApplication {
        val service = buildService(application)
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(1L)
        coEvery { vpsService.getServer(1L) } returns VpsService.ServerInfo("active", "1.2.3.4")
        coEvery { vpsService.deleteServer(any()) } just Runs
        // Project creation fails before a ref is captured
        coEvery { managementService.createProject(any()) } throws RuntimeException("Supabase API down")
        coEvery { sshExecutor.waitForSshReady(any()) } just Runs
        coEvery { sshExecutor.waitForSshAuth(any()) } just Runs

        val (dropletId, _, password) = service.createAndProvision("no-project", "user9")
        runCatching { service.provisionDroplet(dropletId, password, "user9") }

        coVerify { vpsService.deleteServer(1L) }
        coVerify(exactly = 0) { managementService.deleteProject(any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildService(application: Application) = DropletProvisioningServiceImpl(
        vpsService = vpsService,
        dnsProvider = dnsProvider,
        firestoreRepository = firestoreRepository,
        agentRepository = agentRepository,
        schemaRepository = schemaRepository,
        managementService = managementService,
        userClientProvider = userClientProvider,
        openClawConnector = openClawConnector,
        sshCommandExecutor = sshExecutor,
        dropletMcpService = mcpService,
        application = application
    )

    /**
     * Configures all mocks for a successful provisioning run.
     * SSH_ENABLED must be false in .env for the nginx/cert phase to be skipped.
     */
    private fun setupHappyPath(projectRef: String, endpoint: String, schema: String) {
        coEvery { vpsService.createServer(any(), any()) } returns VpsService.ServerCreateResult(1L)
        coEvery { vpsService.getServer(1L) } returns VpsService.ServerInfo("active", "1.2.3.4")

        coEvery { managementService.createProject(any()) } returns ProjectResult(projectRef, endpoint)
        coEvery { managementService.waitForProjectActive(any()) } just Runs
        coEvery { managementService.getServiceKey(any()) } returns "service-key"
        every { managementService.resolveSchema(projectRef) } returns schema

        coEvery { sshExecutor.waitForSshReady(any()) } just Runs
        coEvery { sshExecutor.waitForSshAuth(any()) } just Runs
        every { sshExecutor.runSshCommand(any(), any()) } returns ""
        every { sshExecutor.runSshCommandOnce(any(), any()) } returns ""

        coEvery { mcpService.configureMcpTools(any(), any()) } just Runs
        coEvery { dnsProvider.createDnsRecord(any(), any()) } returns "server.suprclaw.com"
        coEvery { openClawConnector.connect(any(), any()) } returns null

        every { userClientProvider.getClient(any(), any(), any()) } returns mockk(relaxed = true)
    }

    // ── MCP configuration during provisioning ─────────────────────────────────

    @Test
    fun `provisionDroplet calls configureMcpTools with default tools`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_mcptest",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_mcptest"
        )

        val (dropletId, _, password) = service.createAndProvision("mcp-test", "user-mcp")
        service.provisionDroplet(dropletId, password, "user-mcp")

        coVerify { mcpService.configureMcpTools(any(), listOf("supabase", "cloud_browser")) }
    }

    @Test
    fun `provisionDroplet passes correct droplet state to configureMcpTools`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_mcpstate",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_mcpstate"
        )

        val (dropletId, _, password) = service.createAndProvision("mcp-state-test", "user-mcp-state")
        service.provisionDroplet(dropletId, password, "user-mcp-state")

        val dropletSlot = slot<UserDropletInternal>()
        coVerify { mcpService.configureMcpTools(capture(dropletSlot), any()) }
        assertEquals("proj_mcpstate", dropletSlot.captured.supabaseProjectRef)
        assertEquals("1.2.3.4", dropletSlot.captured.ipAddress)
    }

    @Test
    fun `provisionDroplet writes cloud browser guidance into lead workspace tools file`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_browsertools",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_browsertools"
        )
        val commands = mutableListOf<String>()
        every { sshExecutor.runSshCommand(any(), any()) } answers {
            commands += secondArg<String>()
            ""
        }
        every { sshExecutor.runSshCommandOnce(any(), any()) } returns ""

        val (dropletId, _, password) = service.createAndProvision("browser-tools", "user-browser-tools")
        service.provisionDroplet(dropletId, password, "user-browser-tools")

        assertTrue(commands.any { it.contains("/home/openclaw/.openclaw/workspace/TOOLS.md") })
        assertTrue(commands.any { it.contains("SUPRCLAW_CLOUD_BROWSER_TOOLS_V1") })
    }

    // ── Full lifecycle: provision → MCP configured → teardown ─────────────────

    @Test
    fun `full lifecycle provision then teardown cleans all resources`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_lifecycle",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_lifecycle"
        )

        // Phase 1: Provision
        val (dropletId, _, password) = service.createAndProvision("lifecycle-test", "user-lifecycle")
        service.provisionDroplet(dropletId, password, "user-lifecycle")

        assertEquals(ProvisioningStatus.PHASE_COMPLETE, service.getStatus(dropletId)?.phase,
            "Expected PHASE_COMPLETE after successful provision")
        coVerify { mcpService.configureMcpTools(any(), listOf("supabase", "cloud_browser")) }

        // Phase 2: Wire up teardown mocks using the droplet that was saved to Firestore
        val savedDroplet = captureLastSavedDroplet()
        coEvery { firestoreRepository.getUserDropletInternal("user-lifecycle") } returns savedDroplet
        coEvery { vpsService.deleteServer(savedDroplet.dropletId) } just Runs
        coEvery { schemaRepository.cleanupUserProject(managementService, "proj_lifecycle", "user-lifecycle") } just Runs
        coEvery { firestoreRepository.deleteUserDroplet("user-lifecycle") } just Runs
        coEvery { firestoreRepository.deleteProjectRefMapping("proj_lifecycle") } just Runs

        // Phase 3: Teardown
        service.teardown("user-lifecycle")

        coVerify { vpsService.deleteServer(savedDroplet.dropletId) }
        coVerify { schemaRepository.cleanupUserProject(managementService, "proj_lifecycle", "user-lifecycle") }
        coVerify { firestoreRepository.deleteUserDroplet("user-lifecycle") }
        coVerify { firestoreRepository.deleteProjectRefMapping("proj_lifecycle") }
        assertNull(service.getStatus(savedDroplet.dropletId),
            "Status must be cleared after teardown")
    }

    @Test
    fun `full lifecycle teardown after MCP failure still cleans VPS and Supabase`() = testApplication {
        val service = buildService(application)
        setupHappyPath(
            projectRef = "proj_mcpfail",
            endpoint = "https://supabase.suprclaw.com",
            schema = "proj_mcpfail"
        )
        // MCP configuration throws after project is created — provisioning should roll back
        coEvery { mcpService.configureMcpTools(any(), any()) } throws RuntimeException("MCP config failed")
        coEvery { vpsService.deleteServer(any()) } just Runs
        coEvery { managementService.deleteProject(any()) } just Runs

        val (dropletId, _, password) = service.createAndProvision("mcp-fail-test", "user-mcpfail")
        assertFailsWith<Exception> {
            service.provisionDroplet(dropletId, password, "user-mcpfail")
        }

        coVerify { vpsService.deleteServer(1L) }
        coVerify { managementService.deleteProject("proj_mcpfail") }
        assertEquals(ProvisioningStatus.PHASE_FAILED, service.getStatus(dropletId)?.phase,
            "Status must be PHASE_FAILED after MCP config exception")
    }

    // ── Teardown tests ────────────────────────────────────────────────────────

    @Test
    fun `teardown throws IllegalStateException when no droplet found for user`() = testApplication {
        val service = buildService(application)
        coEvery { firestoreRepository.getUserDropletInternal("ghost") } returns null

        assertFailsWith<IllegalStateException> {
            service.teardown("ghost")
        }
    }

    @Test
    fun `teardown deletes VPS server cleanupUserProject and Firestore doc`() = testApplication {
        val service = buildService(application)
        val droplet = UserDropletInternal(
            userId = "user-td",
            dropletId = 77L,
            supabaseProjectRef = "proj_teardown"
        )
        coEvery { firestoreRepository.getUserDropletInternal("user-td") } returns droplet
        coEvery { vpsService.deleteServer(77L) } just Runs
        coEvery { schemaRepository.cleanupUserProject(managementService, "proj_teardown", "user-td") } just Runs
        coEvery { firestoreRepository.deleteUserDroplet("user-td") } just Runs
        coEvery { firestoreRepository.deleteProjectRefMapping("proj_teardown") } just Runs

        service.teardown("user-td")

        coVerify { vpsService.deleteServer(77L) }
        coVerify { schemaRepository.cleanupUserProject(managementService, "proj_teardown", "user-td") }
        coVerify { firestoreRepository.deleteUserDroplet("user-td") }
        coVerify { firestoreRepository.deleteProjectRefMapping("proj_teardown") }
    }

    @Test
    fun `teardown skips cleanupUserProject when supabaseProjectRef is blank`() = testApplication {
        val service = buildService(application)
        val droplet = UserDropletInternal(
            userId = "user-noref",
            dropletId = 88L,
            supabaseProjectRef = ""   // blank — no project was created
        )
        coEvery { firestoreRepository.getUserDropletInternal("user-noref") } returns droplet
        coEvery { vpsService.deleteServer(88L) } just Runs
        coEvery { firestoreRepository.deleteUserDroplet("user-noref") } just Runs

        service.teardown("user-noref")

        coVerify { vpsService.deleteServer(88L) }
        coVerify(exactly = 0) { schemaRepository.cleanupUserProject(any(), any(), any()) }
        coVerify { firestoreRepository.deleteUserDroplet("user-noref") }
        coVerify(exactly = 0) { firestoreRepository.deleteProjectRefMapping(any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun captureLastSavedDroplet(): UserDropletInternal {
        val slot = slot<UserDropletInternal>()
        coVerify { firestoreRepository.saveUserDroplet(capture(slot)) }
        return slot.captured
    }
}
