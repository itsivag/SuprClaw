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
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.Application
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for MCP provisioning and schema isolation.
 *
 * Two test methods:
 *
 * 1. `full provisioning and MCP schema isolation` — creates a real Hetzner VPS + Supabase schema,
 *    configures MCP, asserts isolation, then tears everything down.
 *    Gate: INTEGRATION_TEST=true
 *
 * 2. `MCP phase only` — skips provisioning; uses an already-running VPS and schema.
 *    Gate: INTG_MCP_IP and INTG_MCP_SCHEMA must both be set.
 *    Run with:
 *      INTG_MCP_IP=1.2.3.4 INTG_MCP_SCHEMA=proj_abc123 ./gradlew test \
 *        --tests "com.suprbeta.integration.ProvisioningMcpIntegrationTest.MCP phase only"
 */
class ProvisioningMcpIntegrationTest {

    private val log = LoggerFactory.getLogger(ProvisioningMcpIntegrationTest::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // ── MCP-only test (fast, uses existing VPS) ───────────────────────────────

    /**
     * Focused test that only exercises the MCP configuration + isolation assertions.
     * Requires an already-running VPS (from the base snapshot) and an existing Supabase schema.
     *
     * Required env vars:
     *   INTG_MCP_IP      — public IP of the running VPS
     *   INTG_MCP_SCHEMA  — Supabase schema name (e.g. proj_abc123)
     *
     * Optional:
     *   INTG_MCP_GATEWAY_TOKEN — gateway token written to mcp.env (default: intg-test-gateway-token)
     *   INTG_MCP_DROPLET_ID    — numeric server ID (default: 0, only used for UserDropletInternal)
     */
    @Test(timeout = 10 * 60 * 1000L)
    fun `MCP phase only`() {
        val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
        fun env(k: String) = System.getenv(k) ?: dotenv[k] ?: ""

        val ip = env("INTG_MCP_IP")
        val schema = env("INTG_MCP_SCHEMA")
        assumeTrue("Set INTG_MCP_IP and INTG_MCP_SCHEMA to run this test", ip.isNotBlank() && schema.isNotBlank())

        val gatewayToken = env("INTG_MCP_GATEWAY_TOKEN").ifBlank { "intg-test-gateway-token" }
        val dropletId = env("INTG_MCP_DROPLET_ID").toLongOrNull() ?: 0L

        val app = mockk<Application>(relaxed = true)
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val supabase = SelfHostedSupabaseManagementService(httpClient, app)
        val ssh = SshCommandExecutorImpl(app)
        val mcpSvc = DropletMcpServiceImpl(supabase, ssh, app)

        try {
            runBlocking {
                val supabaseUrl = env("SUPABASE_MCP_URL").ifBlank { env("SUPABASE_SELF_HOSTED_URL") }
                val serviceKey = env("SUPABASE_SELF_HOSTED_SERVICE_KEY")
                runMcpPhase(ip, schema, dropletId, gatewayToken, supabaseUrl, ssh, mcpSvc, supabase, httpClient, serviceKey)
            }
        } finally {
            httpClient.close()
        }
    }

    // ── Full provisioning test ────────────────────────────────────────────────

    @Test(timeout = 25 * 60 * 1000L)
    fun `full provisioning and MCP schema isolation`() {
        val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
        assumeTrue(
            "Set INTEGRATION_TEST=true to run this test",
            System.getenv("INTEGRATION_TEST") == "true" || dotenv["INTEGRATION_TEST"] == "true"
        )

        val app = mockk<Application>(relaxed = true)
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val hetzner = HetznerService(httpClient, app)
        val supabase = SelfHostedSupabaseManagementService(httpClient, app)
        val ssh = SshCommandExecutorImpl(app)
        val mcpSvc = DropletMcpServiceImpl(supabase, ssh, app)

        var serverId: Long? = null
        var schemaName: String? = null

        try {
            runBlocking {
                // Phase 1: Create Supabase schema
                log.info("[INTG] Phase 1: Creating Supabase schema...")
                val projectResult = supabase.createProject("intg-test")
                schemaName = projectResult.projectRef
                log.info("[INTG] Schema created: $schemaName")

                // Phase 2: Create sentinel table
                log.info("[INTG] Phase 2: Creating integration_sentinel table in $schemaName...")
                supabase.runSql(
                    schemaName,
                    "CREATE TABLE IF NOT EXISTS public.integration_sentinel (id SERIAL PRIMARY KEY, label TEXT)"
                )
                log.info("[INTG] Table integration_sentinel created")

                // Phase 3: Create Hetzner server
                val serverName = "intg-${schemaName.takeLast(6)}"
                log.info("[INTG] Phase 3: Creating Hetzner server $serverName...")
                val createResult = hetzner.createServer(serverName, "unused")
                serverId = createResult.serverId
                log.info("[INTG] Server $serverId created")

                // Phase 4: Poll until active
                log.info("[INTG] Phase 4: Waiting for server $serverId to become active...")
                val deadline = System.currentTimeMillis() + 5 * 60_000L
                var ip: String? = null
                while (System.currentTimeMillis() < deadline) {
                    val info = hetzner.getServer(serverId)
                    if (info.status == "active") { ip = info.publicIpV4; break }
                    log.info("[INTG] Server status=${info.status}, retrying in 10s...")
                    delay(10_000L)
                }
                assertNotNull(ip, "Server $serverId did not become active within 5 minutes")
                log.info("[INTG] Server $serverId active at $ip")

                // Phase 5: Wait SSH + cloud-init
                log.info("[INTG] Phase 5: Waiting for SSH at $ip...")
                ssh.waitForSshReady(ip)
                ssh.waitForSshAuth(ip)
                log.info("[INTG] SSH auth ready, waiting for cloud-init...")
                ssh.runSshCommand(ip, "cloud-init status --wait")
                log.info("[INTG] Cloud-init complete")

                // Phase 6+7: MCP configure + assert
                val supabaseUrl = (System.getenv("SUPABASE_MCP_URL") ?: dotenv["SUPABASE_MCP_URL"] ?: "")
                    .ifBlank { System.getenv("SUPABASE_SELF_HOSTED_URL") ?: dotenv["SUPABASE_SELF_HOSTED_URL"] ?: "" }
                val serviceKey = System.getenv("SUPABASE_SELF_HOSTED_SERVICE_KEY") ?: dotenv["SUPABASE_SELF_HOSTED_SERVICE_KEY"] ?: ""
                runMcpPhase(ip, schemaName, serverId, "intg-test-gateway-token", supabaseUrl, ssh, mcpSvc, supabase, httpClient, serviceKey)
            }
        } finally {
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

    // ── Shared MCP phase (configure + assert) ────────────────────────────────

    private suspend fun runMcpPhase(
        ip: String,
        schemaName: String,
        dropletId: Long,
        gatewayToken: String,
        supabaseUrl: String,
        ssh: SshCommandExecutorImpl,
        mcpSvc: DropletMcpServiceImpl,
        supabase: SelfHostedSupabaseManagementService,
        httpClient: HttpClient,
        supabaseServiceKey: String
    ) {
        // Phase 6: Configure MCP tools
        log.info("[INTG] Phase 6: Configuring MCP tools on $ip (schema=$schemaName)...")
        val bootstrapDroplet = UserDropletInternal(
            userId = "intg-test",
            dropletId = dropletId,
            ipAddress = ip,
            supabaseProjectRef = schemaName,
            gatewayToken = gatewayToken
        )
        mcpSvc.configureMcpTools(bootstrapDroplet, listOf("supabase"))

        // Start services (restart inside configureMcpTools is a no-op if not yet running)
        ssh.runSshCommand(ip, "sudo systemctl start mcp-auth-proxy mcporter openclaw-gateway 2>/dev/null || true")
        delay(5_000L)

        // Debug: dump what landed on disk
        log.info("[INTG] mcp-routes.json: ${ssh.runSshCommand(ip, "sudo cat /etc/suprclaw/mcp-routes.json 2>&1")}")
        log.info("[INTG] mcporter.json: ${ssh.runSshCommand(ip, "cat /home/openclaw/.mcporter/mcporter.json 2>&1 || cat /home/openclaw/config/mcporter.json 2>&1")}")
        log.info("[INTG] mcp.env keys: ${ssh.runSshCommand(ip, "sudo grep -o '^[^=]*' /etc/suprclaw/mcp.env 2>&1")}")
        log.info("[INTG] service status: ${ssh.runSshCommand(ip, "systemctl is-active mcp-auth-proxy mcporter openclaw-gateway 2>&1 || true")}")
        log.info("[INTG] mcporter status: ${ssh.runSshCommand(ip, "mcporter status 2>&1 || true")}")

        log.info("[INTG] Phase 6 complete")

        // Phase 7: Schema-isolation assertions
        log.info("[INTG] Phase 7: Running MCP assertions...")

        // mcp.env is root:root 600. Source it via sudo bash so OPENCLAW_GATEWAY_TOKEN is set.
        fun mcporterScript(toolArgs: String): String {
            val script = "source /etc/suprclaw/mcp.env\nmcporter call supabase $toolArgs\n"
            val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
            return "echo $b64 | base64 -d | sudo bash"
        }

        @Serializable
        data class TableRow(val schema: String, val name: String)

        fun parseTables(jsonText: String): List<TableRow> =
            runCatching { json.decodeFromString<List<TableRow>>(jsonText) }.getOrElse { emptyList() }

        // Fetch tables for relevant schemas in one call (API expects an array payload).
        log.info("[INTG] 7a: list_tables (target schemas)...")
        val schemasArg = "[\"$schemaName\",\"public\",\"proj_00000000\"]"
        val tablesJson = ssh.runSshCommand(ip, mcporterScript("list_tables '$schemasArg'"))
        log.info("[INTG] list_tables result: $tablesJson")
        val tables = parseTables(tablesJson)

        // 7a: own schema must contain integration_sentinel
        assertTrue(
            tables.any { it.schema == schemaName && it.name == "integration_sentinel" },
            "Expected integration_sentinel in own schema ($schemaName). Got: $tablesJson"
        )
        log.info("[INTG] 7a PASS")

        // 7b: public schema must NOT contain integration_sentinel
        assertFalse(
            tables.any { it.schema == "public" && it.name == "integration_sentinel" },
            "integration_sentinel must NOT appear in public schema. Got: $tablesJson"
        )
        log.info("[INTG] 7b PASS")

        // 7c: foreign user schema must NOT contain integration_sentinel
        assertFalse(
            tables.any { it.schema == "proj_00000000" && it.name == "integration_sentinel" },
            "integration_sentinel must NOT appear in proj_00000000. Got: $tablesJson"
        )
        log.info("[INTG] 7c PASS")

        // 7d: execute_sql operational check
        log.info("[INTG] 7d: execute_sql operational check (SELECT 42)...")
        val execResult = ssh.runSshCommand(ip, mcporterScript("execute_sql query=\"SELECT 42\""))
        log.info("[INTG] 7d result: $execResult")
        assertTrue(
            execResult.contains("42"),
            "execute_sql must be operational (SELECT 42 should return 42). Got: $execResult"
        )
        log.info("[INTG] 7d PASS")

        // 7e: SUPABASE_ACCESS_TOKEN in mcp.env is a scoped JWT (role=proj_xxx_rpc, not service_role)
        log.info("[INTG] 7e: Verifying scoped JWT in mcp.env...")
        val mcpEnvContent = ssh.runSshCommand(ip, "sudo cat /etc/suprclaw/mcp.env 2>&1")
        val accessTokenLine = mcpEnvContent.lines().find { it.startsWith("SUPABASE_ACCESS_TOKEN=") }
        assertNotNull(accessTokenLine, "mcp.env must contain SUPABASE_ACCESS_TOKEN")
        val accessToken = accessTokenLine.removePrefix("SUPABASE_ACCESS_TOKEN=").trim()
        val jwtParts = accessToken.split(".")
        assertTrue(
            jwtParts.size == 3,
            "SUPABASE_ACCESS_TOKEN must be a JWT (3 dot-separated parts — ${jwtParts.size} found). " +
            "Check that SUPABASE_SELF_HOSTED_JWT_SECRET is set in .env"
        )
        val payloadB64 = jwtParts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
        val jwtPayload = String(Base64.getUrlDecoder().decode(payloadB64))
        log.info("[INTG] 7e JWT payload: $jwtPayload")
        assertTrue(
            jwtPayload.contains("${schemaName}_rpc"),
            "JWT role must be ${schemaName}_rpc, got: $jwtPayload"
        )
        assertFalse(
            jwtPayload.contains("service_role"),
            "JWT must NOT use service_role, got: $jwtPayload"
        )
        log.info("[INTG] 7e PASS (scoped role ${schemaName}_rpc confirmed in JWT)")

        // ── Diag A: confirm the role exists in pg_roles ──────────────────────
        val roleExists = runCatching {
            supabase.runSql(
                schemaName,
                "DO \$\$ BEGIN " +
                "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${schemaName}_rpc') " +
                "THEN RAISE EXCEPTION 'role_missing'; END IF; END \$\$;"
            )
            true
        }.getOrElse { e ->
            if (e.message?.contains("role_missing") == true) false else throw e
        }
        log.info("[INTG] DIAG A: role ${schemaName}_rpc exists in pg_roles = $roleExists")
        assertTrue(roleExists, "Role ${schemaName}_rpc must exist in pg_roles before isolation test")

        // 7f: Scoped JWT cannot access another user's schema table via PostgREST.
        // The proj_xxx_rpc role has USAGE only on its own schema → PostgREST returns 403.
        // We use the integration_sentinel table which the full provisioning test creates in each schema.
        // apikey satisfies Kong key-auth; Authorization: Bearer sets the Postgres role in PostgREST.
        log.info("[INTG] 7f: Verifying scoped JWT is denied access to another user schema (PostgREST)...")
        // Find a foreign schema — any proj_ schema that is not ours and is in PGRST_DB_SCHEMAS.
        val foreignSchema = listOf(
            "proj_196891d2", "proj_0fc12ade", "proj_020709a2", "proj_e793ea3c",
            "proj_bfd493d1", "proj_95cc4da7", "proj_7368c9b2", "proj_cd803194", "proj_0771b58d"
        ).first { it != schemaName }
        val response7f = httpClient.get("$supabaseUrl/rest/v1/integration_sentinel") {
            header("apikey", supabaseServiceKey)
            header("Authorization", "Bearer $accessToken")
            header("Accept-Profile", foreignSchema)
        }
        val foreignStatus = response7f.status.value
        log.info("[INTG] 7f foreign schema ($foreignSchema) HTTP status: $foreignStatus  body: ${response7f.bodyAsText().take(200)}")
        assertTrue(
            foreignStatus in 400..499,
            "Scoped JWT must NOT access foreign schema $foreignSchema — expected 4xx, got: $foreignStatus"
        )
        log.info("[INTG] 7f PASS (foreign schema denied: HTTP $foreignStatus)")

        // 7g: Scoped JWT CAN access its own schema table via PostgREST → 200 OK.
        log.info("[INTG] 7g: Verifying scoped JWT can access own schema $schemaName (PostgREST)...")
        val response7g = httpClient.get("$supabaseUrl/rest/v1/integration_sentinel") {
            header("apikey", supabaseServiceKey)
            header("Authorization", "Bearer $accessToken")
            header("Accept-Profile", schemaName)
        }
        val ownStatus = response7g.status.value
        log.info("[INTG] 7g own schema HTTP status: $ownStatus  body: ${response7g.bodyAsText().take(200)}")
        assertTrue(
            ownStatus == 200,
            "Scoped JWT must access own schema $schemaName — expected 200, got: $ownStatus"
        )
        log.info("[INTG] 7g PASS (own schema accessible: HTTP $ownStatus)")

        // 7h: execute_sql cannot access another user's schema table
        log.info("[INTG] 7h: Verifying execute_sql cannot access foreign schema table...")
        val crossSchemaResult = ssh.runSshCommand(ip,
            mcporterScript("execute_sql query=\"SELECT * FROM ${foreignSchema}.integration_sentinel\""))
        log.info("[INTG] 7h result: $crossSchemaResult")
        assertTrue(
            crossSchemaResult.contains("error") || crossSchemaResult.contains("denied") ||
            crossSchemaResult.contains("permission") || crossSchemaResult.contains("42501"),
            "execute_sql must NOT access foreign schema table. Got: $crossSchemaResult"
        )
        log.info("[INTG] 7h PASS")

        log.info("[INTG] ✅ All MCP assertions passed")
    }
}
