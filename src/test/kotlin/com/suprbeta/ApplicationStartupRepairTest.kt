package com.suprbeta

import com.suprbeta.supabase.SupabaseManagementService
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test

class ApplicationStartupRepairTest {

    @Test
    fun `installSupabaseStartupRepair reconciles configuration on application start`() = testApplication {
        val managementService = mockk<SupabaseManagementService>(relaxed = true)

        application {
            installSupabaseStartupRepair(managementService)
        }

        client.get("/")

        coVerify(timeout = 2_000) { managementService.reconcileConfiguration() }
    }
}
