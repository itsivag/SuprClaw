package com.suprbeta.connector

import com.suprbeta.digitalocean.DropletMcpService
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.server.application.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorServiceTest {
    private val firestoreRepository = mockk<FirestoreRepository>(relaxed = true)
    private val dropletMcpService = mockk<DropletMcpService>(relaxed = true)
    private val nangoService = mockk<NangoService>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    private fun service(): ConnectorServiceImpl = ConnectorServiceImpl(
        firestoreRepository = firestoreRepository,
        dropletMcpService = dropletMcpService,
        nangoService = nangoService,
        application = application
    )

    @Test
    fun `updateConnectorPolicy clears provider defaults on sibling accounts`() = runTest {
        val target = ConnectorInternal(
            provider = "google",
            accountId = "acc-primary",
            connectionId = "conn-primary",
            providerConfigKey = "google-prod",
            allowedAgents = listOf("researcher"),
            isDefaultForProvider = false,
            defaultForAgents = emptyList(),
            createdAt = "2026-03-15T00:00:00Z",
            updatedAt = "2026-03-15T00:00:00Z"
        )
        val sibling = ConnectorInternal(
            provider = "google",
            accountId = "acc-sibling",
            connectionId = "conn-sibling",
            providerConfigKey = "google-prod",
            allowedAgents = listOf("writer"),
            isDefaultForProvider = true,
            defaultForAgents = listOf("content-writer"),
            createdAt = "2026-03-14T00:00:00Z",
            updatedAt = "2026-03-14T00:00:00Z"
        )

        coEvery { firestoreRepository.getUserConnectorInternalById("user-1", "acc-primary") } returns target
        coEvery { firestoreRepository.listUserConnectorsByProviderInternal("user-1", "google") } returns listOf(target, sibling)

        val saved = mutableListOf<ConnectorInternal>()
        coEvery { firestoreRepository.saveUserConnector("user-1", capture(saved)) } returns Unit

        val result = service().updateConnectorPolicy(
            userId = "user-1",
            provider = "google",
            accountId = "acc-primary",
            allowedAgents = listOf("content-writer"),
            isDefaultForProvider = true,
            defaultForAgents = listOf("content-writer")
        )

        assertEquals("acc-primary", result.accountId)
        assertEquals(listOf("content-writer"), result.allowedAgents)
        assertTrue(result.isDefaultForProvider)
        assertEquals(listOf("content-writer"), result.defaultForAgents)

        val savedSibling = saved.last { it.accountId == "acc-sibling" }
        assertFalse(savedSibling.isDefaultForProvider)
        assertTrue(savedSibling.defaultForAgents.isEmpty())
    }

    @Test
    fun `handleNangoWebhook creation stores connector and completes session`() = runTest {
        val session = ConnectorSessionInternal(
            id = "session-123",
            userId = "user-1",
            status = ConnectorSessionStatus.PENDING,
            providerInternal = "nango",
            createdAt = "2026-03-15T00:00:00Z",
            updatedAt = "2026-03-15T00:00:00Z",
            expiresAt = "2026-03-15T01:00:00Z",
            provider = "google",
            providerConfigKey = "google-prod",
            nangoSessionToken = "token"
        )
        val record = NangoConnectionRecord(
            connectionId = "conn-123",
            provider = "google",
            providerConfigKey = "google-prod",
            createdAt = "2026-03-15T00:00:00Z",
            updatedAt = "2026-03-15T00:10:00Z",
            tags = mapOf(
                "end_user_id" to "user-1",
                "end_user_email" to "writer@example.com",
                "end_user_display_name" to "Writer"
            ),
            metadata = emptyMap(),
            errors = emptyList(),
            raw = kotlinx.serialization.json.buildJsonObject {
                put("provider", kotlinx.serialization.json.JsonPrimitive("google"))
                put("provider_config_key", kotlinx.serialization.json.JsonPrimitive("google-prod"))
                put("connection_id", kotlinx.serialization.json.JsonPrimitive("conn-123"))
            }
        )
        val payload = """
            {
              "type": "auth",
              "operation": "creation",
              "success": true,
              "provider": "google",
              "providerConfigKey": "google-prod",
              "connectionId": "conn-123",
              "tags": {
                "end_user_id": "user-1",
                "end_user_email": "writer@example.com",
                "suprclaw_session_id": "session-123"
              }
            }
        """.trimIndent()

        every { nangoService.verifyWebhookSignature(payload, "valid") } returns true
        coEvery { firestoreRepository.getConnectorSession("session-123") } returns session
        coEvery { firestoreRepository.findUserConnectorByConnectionInternal("user-1", "google", "conn-123") } returns null
        coEvery { nangoService.getConnection("google-prod", "conn-123") } returns record
        coEvery { firestoreRepository.listUserConnectorsByProviderInternal("user-1", "google") } returns emptyList()

        val savedConnector = slot<ConnectorInternal>()
        val savedSession = slot<ConnectorSessionInternal>()
        coEvery { firestoreRepository.saveUserConnector("user-1", capture(savedConnector)) } returns Unit
        coEvery { firestoreRepository.saveConnectorSession(capture(savedSession)) } returns Unit

        service().handleNangoWebhook(payload, "valid")

        assertEquals("conn-123", savedConnector.captured.accountId)
        assertEquals("writer@example.com", savedConnector.captured.email)
        assertEquals("Writer", savedConnector.captured.displayName)
        assertEquals(ConnectorAccountStatus.CONNECTED, savedConnector.captured.status)
        assertTrue(savedConnector.captured.isDefaultForProvider)

        assertEquals(ConnectorSessionStatus.COMPLETED, savedSession.captured.status)
        assertEquals("conn-123", savedSession.captured.accountId)
        assertEquals("conn-123", savedSession.captured.connectionId)
    }
}
