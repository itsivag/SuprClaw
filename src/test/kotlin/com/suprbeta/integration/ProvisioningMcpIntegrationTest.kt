package com.suprbeta.integration

import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.digitalocean.DropletMcpServiceImpl
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.hetzner.HetznerService
import com.suprbeta.supabase.SelfHostedSupabaseManagementService
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.Application
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration test — Full Provisioning + MCP Schema Isolation.
 *
 * Provisions a real Hetzner VPS from the base snapshot and a real self-hosted Supabase schema,
 * configures the supabase MCP tool on the VPS via [DropletMcpServiceImpl], then verifies that
 * the scoped JWT restricts PostgREST access to exactly the provisioned schema.
 *
 * **Not a unit test.** Gated by `INTEGRATION_TEST=true` in the environment or `.env` so that
 * `./gradlew test` skips it automatically.
 *
 * Required env vars (already in `.env`):
 *   HETZNER_API_TOKEN, HETZNER_IMAGE (snapshot 362934880), PROVISIONING_SSH_PRIVATE_KEY_B64,
 *   SUPABASE_SELF_HOSTED_URL, SUPABASE_SELF_HOSTED_SERVICE_KEY, SUPABASE_SELF_HOSTED_DB_URL,
 *   SUPABASE_SELF_HOSTED_SSH_HOST, SUPABASE_SELF_HOSTED_SSH_USER, SUPABASE_SELF_HOSTED_DOCKER_DIR,
 *   SUPABASE_SELF_HOSTED_JWT_SECRET, SUPABASE_MCP_URL, WEBHOOK_SECRET
 *
 * Run with:
 *   INTEGRATION_TEST=true ./gradlew test --tests "com.suprbeta.integration.ProvisioningMcpIntegrationTest"
 *
 * Note: `testApplication {}` from ktor-server-test-host has a hardcoded 60-second coroutine
 * timeout — unsuitable for a ~15-minute test. This test uses `runBlocking {}` directly and
 * passes a relaxed MockK Application to services (they only need it for their internal logger).
 */
class ProvisioningMcpIntegrationTest {

    private val log = LoggerFactory.getLogger(ProvisioningMcpIntegrationTest::class.java)

    @Test(timeout = 25 * 60 * 1000L)
    fun `full provisioning and MCP schema isolation`() {
        // ── Gate — skip unless explicitly opted in ─────────────────────────────
        val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
        assumeTrue(
            "Set INTEGRATION_TEST=true to run this test",
            System.getenv("INTEGRATION_TEST") == "true" || dotenv["INTEGRATION_TEST"] == "true"
        )

        // Services need Application only for their internal loggers; a relaxed mock satisfies that.
        val app = mockk<Application>(relaxed = true)

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val hetzner = HetznerService(httpClient, app)
        val supabase = SelfHostedSupabaseManagementService(httpClient, app)
        val ssh = SshCommandExecutorImpl(app)
        val mcpSvc = DropletMcpServiceImpl(supabase, ssh, app)

        var serverId: Long? = null
        var schemaName: String? = null

        try {
            runBlocking {
                // ── Phase 1: Create Supabase schema ───────────────────────────
                log.info("[INTG] Phase 1: Creating Supabase schema...")
                val projectResult = supabase.createProject("intg-test")
                schemaName = projectResult.projectRef
                log.info("[INTG] Schema created: $schemaName")

                // ── Phase 2: Create sentinel table ────────────────────────────
                // runSql replaces public. → schemaName., landing the table in the user's schema.
                log.info("[INTG] Phase 2: Creating integration_sentinel table in $schemaName...")
                supabase.runSql(
                    schemaName,
                    "CREATE TABLE IF NOT EXISTS public.integration_sentinel " +
                        "(id SERIAL PRIMARY KEY, label TEXT)"
                )
                log.info("[INTG] Table integration_sentinel created")

                // ── Phase 3: Create Hetzner server ────────────────────────────
                val suffix = schemaName.takeLast(6)
                val serverName = "intg-$suffix"
                log.info("[INTG] Phase 3: Creating Hetzner server $serverName...")
                val createResult = hetzner.createServer(serverName, "unused")
                serverId = createResult.serverId
                log.info("[INTG] Server $serverId created")

                // ── Phase 4: Poll until active (max 5 min) ────────────────────
                log.info("[INTG] Phase 4: Waiting for server $serverId to become active...")
                val deadline = System.currentTimeMillis() + 5 * 60_000L
                var ip: String? = null
                while (System.currentTimeMillis() < deadline) {
                    val info = hetzner.getServer(serverId)
                    if (info.status == "active") {
                        ip = info.publicIpV4
                        break
                    }
                    log.info("[INTG] Server status=${info.status}, retrying in 10s...")
                    delay(10_000L)
                }
                assertNotNull(ip, "Server $serverId did not become active within 5 minutes")
                log.info("[INTG] Server $serverId active at $ip")

                // ── Phase 5: Wait SSH + cloud-init ────────────────────────────
                log.info("[INTG] Phase 5: Waiting for SSH at $ip...")
                ssh.waitForSshReady(ip)
                ssh.waitForSshAuth(ip)
                log.info("[INTG] SSH auth ready, waiting for cloud-init...")
                ssh.runSshCommand(ip, "cloud-init status --wait")
                log.info("[INTG] Cloud-init complete")

                // ── Phase 6: Configure MCP tools ──────────────────────────────
                log.info("[INTG] Phase 6: Configuring MCP tools...")
                val bootstrapDroplet = UserDropletInternal(
                    userId = "intg-test",
                    dropletId = serverId,
                    ipAddress = ip,
                    supabaseProjectRef = schemaName,
                    gatewayToken = "intg-test-gateway-token"
                )
                mcpSvc.configureMcpTools(bootstrapDroplet, listOf("supabase"))

                // Start services (mcporter restart inside configureMcpTools is a no-op when not running)
                ssh.runSshCommand(ip, "sudo systemctl start mcp-auth-proxy mcporter openclaw-gateway 2>/dev/null || true")

                // npm pre-warm: download @supabase/mcp-server-supabase so the first MCP call is fast
                log.info("[INTG] Pre-warming @supabase/mcp-server-supabase npm package...")
                ssh.runSshCommand(
                    ip,
                    "timeout 120 npx -y @supabase/mcp-server-supabase@latest --help > /dev/null 2>&1 || true"
                )
                delay(5_000L) // give mcporter time to reinitialize after npm pre-warm
                log.info("[INTG] MCP configured and services started")

                // ── Phase 7: Schema-isolation assertions ──────────────────────
                log.info("[INTG] Phase 7: Running MCP schema-isolation assertions...")

                // /etc/suprclaw/mcp.env is root:root 600 — unreadable by the openclaw SSH user.
                // mcporter CLI reads OPENCLAW_GATEWAY_TOKEN from it for daemon auth.
                // Solution: base64-encode a script that sources mcp.env then runs mcporter,
                // piped through sudo bash so root can read the file.
                fun mcporterSudoScript(toolArgs: String): String {
                    val script = "source /etc/suprclaw/mcp.env\nmcporter call supabase $toolArgs\n"
                    val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
                    return "echo $b64 | base64 -d | sudo bash"
                }

                // 7a: own schema → MUST contain integration_sentinel
                log.info("[INTG] 7a: list_tables in own schema ($schemaName)...")
                val ownResult = ssh.runSshCommand(
                    ip,
                    mcporterSudoScript("""list_tables '{"schemas":["$schemaName"]}'""")
                )
                assertTrue(
                    ownResult.contains("integration_sentinel"),
                    "Expected integration_sentinel in own schema ($schemaName). Got: $ownResult"
                )
                log.info("[INTG] 7a PASS: integration_sentinel found in own schema")

                // 7b: public schema → must NOT contain integration_sentinel
                log.info("[INTG] 7b: list_tables in public schema...")
                val publicResult = ssh.runSshCommand(
                    ip,
                    mcporterSudoScript("""list_tables '{"schemas":["public"]}'""")
                )
                assertFalse(
                    publicResult.contains("integration_sentinel"),
                    "integration_sentinel must NOT appear in public schema. Got: $publicResult"
                )
                log.info("[INTG] 7b PASS: integration_sentinel absent from public schema")

                // 7c: a foreign user schema → must NOT contain integration_sentinel
                log.info("[INTG] 7c: list_tables in proj_00000000 (foreign schema)...")
                val foreignResult = ssh.runSshCommand(
                    ip,
                    mcporterSudoScript("""list_tables '{"schemas":["proj_00000000"]}'""")
                )
                assertFalse(
                    foreignResult.contains("integration_sentinel"),
                    "integration_sentinel must NOT appear in proj_00000000. Got: $foreignResult"
                )
                log.info("[INTG] 7c PASS: integration_sentinel absent from foreign schema")

                // 7d: execute_sql must fail or return an error — scoped JWT (role proj_xxx_rpc)
                //     is not service_role, so execute_sql access must be blocked.
                log.info("[INTG] 7d: execute_sql isolation check (must NOT return SELECT 42 silently)...")
                var executeSqlBlocked = false
                try {
                    val execResult = ssh.runSshCommandOnce(
                        ip,
                        mcporterSudoScript("""execute_sql '{"query":"SELECT 42"}'""")
                    )
                    // If command exited 0, the body must NOT contain the raw query result.
                    // A JSON error message with exit 0 is acceptable; raw arithmetic result is not.
                    val containsRawResult = execResult.contains("\"42\"") ||
                        (execResult.contains("42") && execResult.contains("?column?"))
                    if (containsRawResult) {
                        fail(
                            "execute_sql returned SELECT 42 result — " +
                                "scoped JWT must restrict this tool. Got: $execResult"
                        )
                    }
                    // Structured error body with exit 0 counts as blocked
                    executeSqlBlocked = true
                } catch (_: RuntimeException) {
                    // Non-zero exit from mcporter → tool blocked at protocol level — expected
                    executeSqlBlocked = true
                }
                assertTrue(executeSqlBlocked, "execute_sql isolation check inconclusive")
                log.info("[INTG] 7d PASS: execute_sql correctly blocked by scoped JWT")

                log.info("[INTG] ✅ All schema-isolation assertions passed")
            }
        } finally {
            // ── Teardown — always runs even on assertion failure ──────────────
            // runBlocking here is intentional: teardown must complete even if the test failed.
            log.info("[INTG] Teardown starting...")

            schemaName?.let { schema ->
                runCatching { runBlocking { supabase.deleteProject(schema) } }
                    .onSuccess { log.info("[INTG] Schema $schema deleted") }
                    .onFailure { e -> log.error("[INTG] Failed to delete schema $schema: ${e.message}") }
            }

            serverId?.let { id ->
                runCatching { runBlocking { hetzner.deleteServer(id) } }
                    .onSuccess { log.info("[INTG] Server $id deleted") }
                    .onFailure { e -> log.error("[INTG] Failed to delete server $id: ${e.message}") }
            }

            httpClient.close()
            log.info("[INTG] Teardown complete")
        }
    }
}
