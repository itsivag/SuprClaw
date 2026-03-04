package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.supabase.SupabaseManagementService
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.*
import java.util.Base64
import kotlin.test.*
import kotlin.test.Test

/**
 * Unit tests for [DropletMcpServiceImpl].
 *
 * Tests JWT generation, mcp-routes.json upstream selection, mcp.env JWT/fallback behaviour,
 * and mcporter.json URL format using a testable subclass that overrides env().
 */
class DropletMcpServiceTest {

    private val sshExecutor = mockk<SshCommandExecutor>()
    private val managementService = mockk<SupabaseManagementService>(relaxed = true)

    private val testDroplet = UserDropletInternal(
        userId = "user1",
        dropletId = 1L,
        ipAddress = "1.2.3.4",
        supabaseProjectRef = "proj_abc12345",
        gatewayToken = "gw-token"
    )

    /** Builds a testable service where env() returns values from [testEnv] (blank for missing). */
    private fun buildService(application: Application, testEnv: Map<String, String> = emptyMap()): DropletMcpServiceImpl =
        object : DropletMcpServiceImpl(managementService, sshExecutor, application) {
            override fun env(key: String): String = testEnv[key] ?: ""
        }

    /** Intercepts all SSH commands and returns them as a list for inspection. */
    private fun captureCommands(): MutableList<String> {
        val commands = mutableListOf<String>()
        every { sshExecutor.runSshCommand(any(), any()) } answers {
            commands.add(secondArg())
            ""
        }
        return commands
    }

    private fun decodeBase64FromCmd(cmd: String): String {
        val b64 = cmd.substringAfter("echo ").substringBefore(" | base64")
        return String(Base64.getDecoder().decode(b64))
    }

    // ── JWT: blank secret ─────────────────────────────────────────────────

    @Test
    fun `generateScopedJwt returns empty string when secret is blank`() = testApplication {
        val service = buildService(application)
        val jwt = service.generateScopedJwt("proj_abc12345")
        assertTrue(jwt.isEmpty(), "Expected empty JWT when SUPABASE_SELF_HOSTED_JWT_SECRET is blank")
    }

    // ── JWT: structure ────────────────────────────────────────────────────

    @Test
    fun `generateScopedJwt returns three-part JWT`() = testApplication {
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))
        val jwt = service.generateScopedJwt("proj_abc12345")
        assertEquals(3, jwt.split(".").size, "JWT must be header.payload.signature")
    }

    @Test
    fun `generateScopedJwt header declares HS256 algorithm`() = testApplication {
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))
        val jwt = service.generateScopedJwt("proj_abc12345")
        val header = String(Base64.getUrlDecoder().decode(jwt.split(".")[0]))
        assertTrue(header.contains("\"HS256\""), "Header alg must be HS256, got: $header")
        assertTrue(header.contains("\"JWT\""),    "Header typ must be JWT, got: $header")
    }

    @Test
    fun `generateScopedJwt payload contains scoped role`() = testApplication {
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))
        val jwt = service.generateScopedJwt("proj_abc12345")
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("\"proj_abc12345_rpc\""),
            "Payload role must be proj_abc12345_rpc, got: $payload")
    }

    @Test
    fun `generateScopedJwt payload has supabase issuer`() = testApplication {
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))
        val jwt = service.generateScopedJwt("proj_abc12345")
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("\"supabase\""), "Payload iss must be supabase, got: $payload")
    }

    @Test
    fun `generateScopedJwt role suffix is rpc regardless of schema prefix`() = testApplication {
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))
        val jwt = service.generateScopedJwt("proj_deadbeef")
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("\"proj_deadbeef_rpc\""),
            "Role must use schema name + _rpc suffix, got: $payload")
    }

    // ── mcp-routes.json: upstream selection ───────────────────────────────

    @Test
    fun `mcp-routes uses SUPABASE_MCP_URL as upstream when set`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application, mapOf(
            "SUPABASE_MCP_URL" to "https://supabase.suprclaw.com"
        ))

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val routesCmd = commands.find { it.contains("mcp-routes.json") && it.contains("base64") }
        assertNotNull(routesCmd, "Expected mcp-routes.json write command")
        val json = decodeBase64FromCmd(routesCmd)
        assertTrue(json.contains("\"upstream\":\"https://supabase.suprclaw.com\""),
            "mcp-routes.json upstream must use SUPABASE_MCP_URL, got: $json")
    }

    @Test
    fun `mcp-routes uses default upstream when SUPABASE_MCP_URL is blank`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application) // SUPABASE_MCP_URL blank

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val routesCmd = commands.find { it.contains("mcp-routes.json") && it.contains("base64") }
        assertNotNull(routesCmd, "Expected mcp-routes.json write command")
        val json = decodeBase64FromCmd(routesCmd)
        assertTrue(json.contains("\"upstream\":\"https://supabase.suprclaw.com\""),
            "mcp-routes.json upstream must fall back to supabase.suprclaw.com, got: $json")
    }

    // ── mcp.env: JWT / fallback ────────────────────────────────────────────

    @Test
    fun `mcp env uses scoped JWT when JWT secret is present`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_JWT_SECRET" to "test-secret-at-least-32-characters-long!!"
        ))

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val envCmd = commands.find { it.contains("mcp.env") && it.contains("base64") }
        assertNotNull(envCmd, "Expected mcp.env write command")
        val content = decodeBase64FromCmd(envCmd)
        val tokenLine = content.lines().find { it.startsWith("SUPABASE_ACCESS_TOKEN=") }
        assertNotNull(tokenLine, "mcp.env must contain SUPABASE_ACCESS_TOKEN")
        val token = tokenLine.removePrefix("SUPABASE_ACCESS_TOKEN=")
        val parts = token.split(".")
        assertEquals(3, parts.size, "SUPABASE_ACCESS_TOKEN must be a valid JWT")
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(payload.contains("\"proj_abc12345_rpc\""),
            "JWT role must be scoped to proj_abc12345_rpc, got: $payload")
    }

    @Test
    fun `mcp env falls back to managementToken when JWT secret is blank`() = testApplication {
        val commands = captureCommands()
        every { managementService.managementToken } returns "mgmt-fallback-token"
        val service = buildService(application) // no JWT secret

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val envCmd = commands.find { it.contains("mcp.env") && it.contains("base64") }
        assertNotNull(envCmd, "Expected mcp.env write command")
        val content = decodeBase64FromCmd(envCmd)
        assertTrue(content.contains("SUPABASE_ACCESS_TOKEN=mgmt-fallback-token"),
            "mcp.env must fall back to managementToken, got: $content")
    }

    // ── mcporter.json: always URL mode ────────────────────────────────────

    @Test
    fun `mcporter json always uses URL config for supabase`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application)

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val mcporterCmd = commands.find { it.contains("mcporter.json") && it.contains("base64 -d >") }
        assertNotNull(mcporterCmd, "Expected mcporter.json write command")
        val json = decodeBase64FromCmd(mcporterCmd)
        assertTrue(json.contains("\"url\""),
            "mcporter.json must have url config, got: $json")
        assertTrue(json.contains("\"lifecycle\""),
            "mcporter.json must have lifecycle field, got: $json")
        assertFalse(json.contains("\"command\""),
            "mcporter.json must NOT have command field, got: $json")
    }

    @Test
    fun `mcporter json supabase URL contains project_ref`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application)

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val mcporterCmd = commands.find { it.contains("mcporter.json") && it.contains("base64 -d >") }
        assertNotNull(mcporterCmd, "Expected mcporter.json write command")
        val json = decodeBase64FromCmd(mcporterCmd)
        assertTrue(json.contains("?project_ref=proj_abc12345"),
            "mcporter.json supabase URL must contain ?project_ref=proj_abc12345, got: $json")
    }

    // ── mcporter service restart ───────────────────────────────────────────

    @Test
    fun `configureMcpTools always restarts mcporter service after writing config`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application)

        service.configureMcpTools(testDroplet, listOf("supabase"))

        assertTrue(commands.any { it.contains("restart") && it.contains("mcporter") },
            "Expected systemctl restart mcporter command")
    }

    @Test
    fun `configureMcpTools mcporter restart comes after config writes`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application)

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val restartIdx = commands.indexOfFirst { it.contains("restart") && it.contains("mcporter") }
        val configIdx  = commands.indexOfFirst { it.contains("mcporter.json") }
        assertTrue(restartIdx > configIdx, "Restart must come after mcporter.json is written")
    }

    // ── mcp.env ────────────────────────────────────────────────────────────

    @Test
    fun `mcp env includes SUPABASE_API_KEY from SUPABASE_SELF_HOSTED_SERVICE_KEY`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application, mapOf(
            "SUPABASE_SELF_HOSTED_SERVICE_KEY" to "test-service-key"
        ))

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val envCmd = commands.find { it.contains("mcp.env") && it.contains("base64") }
        assertNotNull(envCmd, "Expected mcp.env write command")
        val content = decodeBase64FromCmd(envCmd)
        assertTrue(content.contains("SUPABASE_API_KEY=test-service-key"),
            "mcp.env must contain SUPABASE_API_KEY from SUPABASE_SELF_HOSTED_SERVICE_KEY, got: $content")
    }

    @Test
    fun `configureMcpTools writes mcp env with gateway token`() = testApplication {
        val commands = captureCommands()
        val service = buildService(application)

        service.configureMcpTools(testDroplet, listOf("supabase"))

        val envCmd = commands.find { it.contains("mcp.env") && it.contains("base64") }
        assertNotNull(envCmd, "Expected mcp.env write command")
        val content = decodeBase64FromCmd(envCmd)
        assertTrue(content.contains("OPENCLAW_GATEWAY_TOKEN=gw-token"),
            "mcp.env must contain gateway token, got: $content")
        assertTrue(content.contains("SUPABASE_PROJECT_REF=proj_abc12345"),
            "mcp.env must contain project ref, got: $content")
    }
}
