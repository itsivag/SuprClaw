package com.suprbeta.supabase

import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupabaseSchemaRepositoryTest {

    @Test
    fun `initializeUserProject reloads PostgREST schema cache after DDL`() = testApplication {
        val managementService = mockk<SupabaseManagementService>()
        coEvery { managementService.runSql(any(), any()) } returns Unit
        coEvery { managementService.createDatabaseWebhook(any()) } returns Unit
        coEvery { managementService.reloadSchemaCache(any()) } returns Unit

        val repository = SupabaseSchemaRepository(application)

        runTest {
            repository.initializeUserProject(
                managementService = managementService,
                projectRef = "proj_12345678",
                userId = "user-1",
                vpsGatewayUrl = "https://example.com",
                gatewayToken = "token"
            )
        }

        coVerifyOrder {
            managementService.runSql("proj_12345678", any())
            managementService.createDatabaseWebhook("proj_12345678")
            managementService.reloadSchemaCache("proj_12345678")
        }
    }

    @Test
    fun `user project schema keeps task foreign keys delete-safe`() {
        val taskForeignKeyLines = USER_PROJECT_SQL
            .lineSequence()
            .map { it.trim().trimEnd(',') }
            .filter { "REFERENCES public.tasks(id)" in it }
            .toList()

        assertEquals(5, taskForeignKeyLines.size, "Unexpected number of foreign keys referencing tasks(id)")
        taskForeignKeyLines.forEach { line ->
            assertTrue(
                line.contains("ON DELETE CASCADE") || line.contains("ON DELETE SET NULL"),
                "Task foreign key must stay non-blocking for deleteTask: $line"
            )
        }
    }

    @Test
    fun `user project schema only contains approved task foreign key clauses`() {
        val actualClauses = USER_PROJECT_SQL
            .lineSequence()
            .map { it.trim().trimEnd(',') }
            .filter { "REFERENCES public.tasks(id)" in it }
            .toSet()

        val expectedClauses = setOf(
            "task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE",
            "task_id UUID REFERENCES public.tasks(id) ON DELETE CASCADE",
            "task_id UUID REFERENCES public.tasks(id) ON DELETE SET NULL"
        )

        assertEquals(expectedClauses, actualClauses)
    }
}
