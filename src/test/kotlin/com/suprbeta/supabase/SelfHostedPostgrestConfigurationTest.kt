package com.suprbeta.supabase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfHostedPostgrestConfigurationTest {

    @Test
    fun `desiredPostgrestSchemas returns base schemas when no tenant schemas exist`() {
        assertEquals(BASE_POSTGREST_SCHEMAS, SelfHostedSupabaseManagementService.desiredPostgrestSchemas(emptyList()))
    }

    @Test
    fun `desiredPostgrestSchemas appends active tenant schemas in sorted order`() {
        val desired = SelfHostedSupabaseManagementService.desiredPostgrestSchemas(
            listOf("proj_b", "proj_a", "public", "proj_b")
        )

        assertEquals(
            listOf("public", "storage", "graphql_public", "proj_a", "proj_b"),
            desired
        )
    }

    @Test
    fun `planPostgrestSchemaReconciliation removes stale dropped schemas`() {
        val plan = SelfHostedSupabaseManagementService.planPostgrestSchemaReconciliation(
            currentConfiguredSchemas = listOf("public", "storage", "graphql_public", "proj_live", "proj_stale"),
            tenantSchemasFromDb = listOf("proj_live")
        )

        assertEquals(listOf("proj_stale"), plan.staleSchemas)
        assertEquals(listOf("public", "storage", "graphql_public", "proj_live"), plan.desiredSchemas)
        assertTrue(plan.restartRequired)
    }

    @Test
    fun `reconcileConfiguration rewrites stale env schema list from live database state`() = runTest {
        val sshCommands = mutableListOf<String>()
        val service = newService(
            jdbcQueryExecutor = { listOf("proj_live", "proj_new") },
            sshCommandRunner = { _, _, command ->
                sshCommands += command
                when {
                    command.contains("grep '^PGRST_DB_SCHEMAS='") ->
                        "public,storage,graphql_public,proj_live,proj_stale"
                    command.contains("docker compose up -d --force-recreate rest") -> ""
                    else -> error("Unexpected SSH command: $command")
                }
            }
        )

        service.reconcileConfiguration()

        assertEquals(2, sshCommands.size)
        assertTrue(
            sshCommands[1].contains("PGRST_DB_SCHEMAS=public,storage,graphql_public,proj_live,proj_new"),
            "Expected updated schema list in SSH command, got: ${sshCommands[1]}"
        )
    }

    @Test
    fun `reconcileConfiguration skips rest restart when env already matches database state`() = runTest {
        val sshCommands = mutableListOf<String>()
        val service = newService(
            jdbcQueryExecutor = { listOf("proj_live") },
            sshCommandRunner = { _, _, command ->
                sshCommands += command
                when {
                    command.contains("grep '^PGRST_DB_SCHEMAS='") ->
                        "public,storage,graphql_public,proj_live"
                    command.contains("docker compose up -d --force-recreate rest") ->
                        error("Did not expect a rest restart when schemas already match")
                    else -> error("Unexpected SSH command: $command")
                }
            }
        )

        service.reconcileConfiguration()

        assertEquals(1, sshCommands.size)
    }

    @Test
    fun `reloadSchemaCache only notifies PostgREST and never uses SSH`() = runTest {
        val sqlStatements = mutableListOf<String>()
        val sshCalls = AtomicInteger(0)
        val client = HttpClient(MockEngine {
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        })

        try {
            val service = newService(
                httpClient = client,
                jdbcStatementExecutor = { statement -> sqlStatements += statement },
                sshCommandRunner = { _, _, _ ->
                    sshCalls.incrementAndGet()
                    error("reloadSchemaCache must not use SSH")
                }
            )

            service.reloadSchemaCache("proj_abc12345")
        } finally {
            client.close()
        }

        assertEquals(listOf("NOTIFY pgrst, 'reload schema'"), sqlStatements)
        assertEquals(0, sshCalls.get())
    }

    @Test
    fun `deleteProject reconciles PostgREST config after dropping schema`() = runTest {
        val sqlStatements = mutableListOf<String>()
        val sshCommands = mutableListOf<String>()
        val service = newService(
            jdbcStatementExecutor = { statement -> sqlStatements += statement },
            jdbcQueryExecutor = { emptyList() },
            sshCommandRunner = { _, _, command ->
                sshCommands += command
                when {
                    command.contains("grep '^PGRST_DB_SCHEMAS='") ->
                        "public,storage,graphql_public,proj_dropme"
                    command.contains("docker compose up -d --force-recreate rest") -> ""
                    else -> error("Unexpected SSH command: $command")
                }
            }
        )

        service.deleteProject("proj_dropme")

        assertTrue(sqlStatements.contains("DROP SCHEMA IF EXISTS proj_dropme CASCADE"))
        assertTrue(
            sshCommands.last().contains("PGRST_DB_SCHEMAS=public,storage,graphql_public"),
            "Expected dropped schema to be removed from PostgREST config, got: ${sshCommands.last()}"
        )
    }

    @Test
    fun `reconcileConfiguration serializes shared PostgREST config mutations`() = runBlocking {
        val activeCommands = AtomicInteger(0)
        val maxConcurrentCommands = AtomicInteger(0)

        val service = newService(
            jdbcQueryExecutor = { listOf("proj_live") },
            sshCommandRunner = { _, _, command ->
                if (command.contains("docker compose up -d --force-recreate rest")) {
                    val concurrent = activeCommands.incrementAndGet()
                    maxConcurrentCommands.updateAndGet { current -> max(current, concurrent) }
                    Thread.sleep(100)
                    activeCommands.decrementAndGet()
                }

                when {
                    command.contains("grep '^PGRST_DB_SCHEMAS='") -> "public,storage,graphql_public,proj_stale"
                    command.contains("docker compose up -d --force-recreate rest") -> ""
                    else -> error("Unexpected SSH command: $command")
                }
            }
        )

        awaitAll(
            async(Dispatchers.Default) { service.reconcileConfiguration() },
            async(Dispatchers.Default) { service.reconcileConfiguration() }
        )

        assertEquals(1, maxConcurrentCommands.get())
    }

    private fun newService(
        httpClient: HttpClient = HttpClient(MockEngine {
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }),
        jdbcStatementExecutor: ((String) -> Unit)? = null,
        jdbcQueryExecutor: ((String) -> List<String>)? = null,
        sshCommandRunner: ((String, String, String) -> String)? = null
    ): SelfHostedSupabaseManagementService =
        SelfHostedSupabaseManagementService(
            httpClient = httpClient,
            application = mockk<Application>(relaxed = true),
            jdbcStatementExecutor = jdbcStatementExecutor,
            jdbcQueryExecutor = jdbcQueryExecutor,
            sshCommandRunner = sshCommandRunner
        )
}
