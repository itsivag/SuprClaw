package com.suprbeta.supabase

import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

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
}
