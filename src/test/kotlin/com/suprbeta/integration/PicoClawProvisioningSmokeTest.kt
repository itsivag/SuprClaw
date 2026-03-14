package com.suprbeta.integration

import com.suprbeta.ProvisioningServices
import com.suprbeta.configureFirebase
import com.suprbeta.configureProvisioning
import com.suprbeta.config.AppConfig
import com.suprbeta.core.CryptoService
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.createHttpClient
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceService
import com.suprbeta.runtime.AgentRuntimeRegistry
import com.suprbeta.runtime.RuntimePaths
import com.suprbeta.supabase.SelfHostedSupabaseManagementService
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SupabaseManagementService
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PicoClawProvisioningSmokeTest {
    private val log = LoggerFactory.getLogger(PicoClawProvisioningSmokeTest::class.java)
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private fun env(key: String): String = System.getenv(key) ?: dotenv[key] ?: ""

    private fun requireProvisioningOptIn() {
        assumeTrue(
            env("INTEGRATION_TEST").equals("true", ignoreCase = true) ||
                env("INTG_PICOCLAW_PROVISION").equals("true", ignoreCase = true)
        ) {
            "Set INTEGRATION_TEST=true or INTG_PICOCLAW_PROVISION=true to run the real PicoClaw provisioning smoke test"
        }

        val required = listOf(
            "HETZNER_API_TOKEN",
            "DOMAIN",
            "PROVISIONING_SSH_PRIVATE_KEY_B64",
            "PROVISIONING_SSH_PUBLIC_KEY",
            "PROVISIONING_SSH_HOST_PRIVATE_KEY_B64",
            "PROVISIONING_SSH_HOST_PUBLIC_KEY",
            "FIREBASE_PROJECT_ID",
            "FIREBASE_CREDENTIALS_PATH",
            "SUPABASE_SELF_HOSTED_URL",
            "SUPABASE_SELF_HOSTED_SERVICE_KEY",
            "SUPABASE_SELF_HOSTED_DB_URL",
            "SUPABASE_SELF_HOSTED_SSH_HOST",
            "SUPABASE_SELF_HOSTED_SSH_USER",
            "WEBHOOK_SECRET"
        ).filter { env(it).isBlank() }
            .filterNot { it == "SUPABASE_SELF_HOSTED_STACK_DIR" && env("SUPABASE_SELF_HOSTED_DOCKER_DIR").isNotBlank() }

        assumeTrue(required.isEmpty()) {
            "Missing required env vars for provisioning smoke test: ${required.joinToString(", ")}"
        }
    }

    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.MINUTES)
    @Test
    fun `provision fresh picoclaw tenant and verify runtime lifecycle`() = runBlocking {
        requireProvisioningOptIn()

        var firestoreRepository: FirestoreRepository? = null
        var provisioningServices: ProvisioningServices? = null
        var sshCommandExecutor: SshCommandExecutor? = null
        var httpClient: HttpClient? = null
        var agentRepository: SupabaseAgentRepository? = null
        var userClientProvider: UserSupabaseClientProvider? = null

        val server = embeddedServer(Netty, port = 0) {
            AppConfig.initialize(this)

            val cryptoService = CryptoService(this)
            val (_, firestore, _) = configureFirebase(cryptoService)
            val createdHttpClient = createHttpClient()
            val createdAgentRepository = SupabaseAgentRepository(this)
            val createdUserClientProvider = UserSupabaseClientProvider()
            val schemaRepository = SupabaseSchemaRepository(this)
            val createdSshExecutor = SshCommandExecutorImpl(this)
            val runtimeRegistry = AgentRuntimeRegistry()
            val managementService: SupabaseManagementService = SelfHostedSupabaseManagementService(createdHttpClient, this)
            val marketplaceService = MarketplaceService(createdHttpClient)

            firestoreRepository = firestore
            httpClient = createdHttpClient
            agentRepository = createdAgentRepository
            userClientProvider = createdUserClientProvider
            sshCommandExecutor = createdSshExecutor
            provisioningServices = configureProvisioning(
                createdHttpClient,
                firestore,
                createdAgentRepository,
                schemaRepository,
                managementService,
                createdUserClientProvider,
                marketplaceService,
                createdSshExecutor,
                runtimeRegistry
            )
        }
        server.start(wait = false)

        val safeFirestoreRepository = checkNotNull(firestoreRepository)
        val safeProvisioningServices = checkNotNull(provisioningServices)
        val safeSshCommandExecutor = checkNotNull(sshCommandExecutor)
        val safeAgentRepository = checkNotNull(agentRepository)
        val safeUserClientProvider = checkNotNull(userClientProvider)

        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val userId = "smoke-picoclaw-$suffix"
        val dropletName = "smoke-$suffix"
        val workerName = "worker$suffix"

        try {
            log.info("[SMOKE] Starting PicoClaw provisioning smoke test for userId={}", userId)

            val createResult = safeProvisioningServices.provisioningService.createAndProvision(
                name = dropletName,
                userId = userId
            )
            val provisioned = safeProvisioningServices.provisioningService.provisionDroplet(
                dropletId = createResult.dropletId,
                password = createResult.password,
                userId = userId
            )

            assertEquals("active", provisioned.status)
            assertTrue(provisioned.gatewayToken.isNotBlank(), "Expected gateway token after provisioning")
            assertTrue(provisioned.gatewayUrl.isNotBlank(), "Expected gateway URL after provisioning")

            val storedDroplet = assertNotNull(safeFirestoreRepository.getUserDropletInternal(userId))
            assertTrue(storedDroplet.isPicoClawRuntime(), "Expected PicoClaw runtime")
            assertEquals("active", storedDroplet.status)
            assertTrue(storedDroplet.supabaseProjectRef.isNotBlank(), "Expected provisioned Supabase project ref")
            assertTrue(storedDroplet.resolveSupabaseUrl().startsWith("http"), "Expected resolvable Supabase URL")

            val containerId = assertNotNull(storedDroplet.containerIdOrNull(), "Expected container-backed deployment")
            val hostIp = storedDroplet.ipAddress
            assertTrue(hostIp.isNotBlank(), "Expected host IP to be persisted")

            val containerStatus = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman inspect --format '{{.State.Status}}' $containerId"
            ).trim()
            assertEquals("running", containerStatus)

            val runtimeConfigCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId test -f ${RuntimePaths.picoclawConfig} && echo OK"
            ).trim()
            assertEquals("OK", runtimeConfigCheck)

            val leadWorkspaceCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId test -f ${RuntimePaths.leadWorkspace}/AGENTS.md && echo OK"
            ).trim()
            assertEquals("OK", leadWorkspaceCheck)

            val leadIdentityCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId test -f ${RuntimePaths.leadWorkspace}/IDENTITY.md && echo OK"
            ).trim()
            assertEquals("OK", leadIdentityCheck)

            val userClient = safeUserClientProvider.getClient(
                storedDroplet.resolveSupabaseUrl(),
                storedDroplet.supabaseServiceKey,
                storedDroplet.supabaseSchema
            )
            val leadAgent = assertNotNull(
                safeAgentRepository.getLeadAgent(userClient),
                "Expected lead agent to be seeded in Supabase"
            )
            assertEquals("Lead", leadAgent.name)
            assertTrue(leadAgent.sessionKey.isNotBlank(), "Expected lead session key")

            safeProvisioningServices.configuringService.createAgent(
                userId = userId,
                name = workerName,
                role = "Provisioning smoke-test worker"
            )

            val workerWorkspace = "${RuntimePaths.runtimeHome}/workspace-$workerName"
            val workerWorkspaceCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId test -f $workerWorkspace/AGENTS.md && echo OK"
            ).trim()
            assertEquals("OK", workerWorkspaceCheck)

            val workerConfigCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId node -e \"const fs=require('fs'); const cfg=JSON.parse(fs.readFileSync('${RuntimePaths.picoclawConfig}','utf8')); process.stdout.write(Array.isArray(cfg.agents?.list) && cfg.agents.list.some((agent) => agent && agent.id === '$workerName') ? 'present' : 'missing')\""
            ).trim()
            assertEquals("present", workerConfigCheck)

            safeProvisioningServices.configuringService.deleteAgent(userId, workerName)

            val deletedWorkerWorkspaceCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId sh -lc \"test ! -d $workerWorkspace && echo REMOVED\""
            ).trim()
            assertEquals("REMOVED", deletedWorkerWorkspaceCheck)

            val deletedWorkerConfigCheck = safeSshCommandExecutor.runSshCommand(
                hostIp,
                "podman exec $containerId node -e \"const fs=require('fs'); const cfg=JSON.parse(fs.readFileSync('${RuntimePaths.picoclawConfig}','utf8')); process.stdout.write(Array.isArray(cfg.agents?.list) && cfg.agents.list.some((agent) => agent && agent.id === '$workerName') ? 'present' : 'missing')\""
            ).trim()
            assertEquals("missing", deletedWorkerConfigCheck)

            log.info("[SMOKE] PicoClaw provisioning smoke test completed successfully for userId={}", userId)
        } finally {
            runCatching {
                if (safeFirestoreRepository.getUserDropletInternal(userId) != null) {
                    log.info("[SMOKE] Tearing down smoke-test tenant {}", userId)
                    safeProvisioningServices.provisioningService.teardown(userId)
                }
            }.onFailure { error ->
                log.error("[SMOKE] Smoke-test teardown failed for userId={}", userId, error)
            }
            httpClient?.close()
            server.stop(1_000, 5_000, TimeUnit.MILLISECONDS)
        }
    }
}
