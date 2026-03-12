package com.suprbeta.supabase

import com.suprbeta.configureSerialization
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.PushNotificationSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookRoutesTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val userClientProvider = mockk<UserSupabaseClientProvider>(relaxed = true)
    private val agentRepository = mockk<SupabaseAgentRepository>(relaxed = true)
    private val pushNotificationSender = mockk<PushNotificationSender>()
    private val httpClient = HttpClient(MockEngine {
        respond("ok")
    })

    private val droplet = UserDropletInternal(
        userId = "user-1",
        supabaseProjectRef = "project-1",
        supabaseServiceKey = "service-key",
        supabaseSchema = "public"
    )

    private fun Application.configureTestModule() {
        configureSerialization()
        configureWebhookRoutes(
            firestoreRepository = firestoreRepository,
            userClientProvider = userClientProvider,
            agentRepository = agentRepository,
            httpClient = httpClient,
            webhookSecret = "secret",
            pushNotificationSender = pushNotificationSender
        )
    }

    @Test
    fun `notification webhook sends push notification on insert`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByProjectRef("project-1") } returns droplet
        coEvery { firestoreRepository.getFcmToken("user-1") } returns "fcm-token"
        coEvery { pushNotificationSender.sendNotification(any(), any(), any(), any()) } returns Unit

        val response = client.post("/webhooks/notifications/project-1") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "type": "INSERT",
                  "table": "notifications",
                  "record": {
                    "id": "notif-1",
                    "type": "task_assigned",
                    "payload": {
                      "title": "Task Assigned",
                      "body": "A task was assigned to an agent.",
                      "taskId": "task-1"
                    }
                  },
                  "schema": "public",
                  "old_record": null
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            pushNotificationSender.sendNotification(
                fcmToken = "fcm-token",
                title = "Task Assigned",
                body = "A task was assigned to an agent.",
                data = match { data ->
                    data["notificationId"] == "notif-1" &&
                        data["type"] == "task_assigned" &&
                        data["taskId"] == "task-1"
                }
            )
        }
    }

    @Test
    fun `notification webhook skips push when user has no fcm token`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByProjectRef("project-1") } returns droplet
        coEvery { firestoreRepository.getFcmToken("user-1") } returns null

        val response = client.post("/webhooks/notifications/project-1") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "type": "INSERT",
                  "table": "notifications",
                  "record": {
                    "id": "notif-1",
                    "type": "task_assigned",
                    "payload": {}
                  },
                  "schema": "public",
                  "old_record": null
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 0) { pushNotificationSender.sendNotification(any(), any(), any(), any()) }
    }

    @Test
    fun `notification webhook preserves browser payload fields for push delivery`() = testApplication {
        application { configureTestModule() }

        coEvery { firestoreRepository.getUserDropletInternalByProjectRef("project-1") } returns droplet
        coEvery { firestoreRepository.getFcmToken("user-1") } returns "fcm-token"
        coEvery { pushNotificationSender.sendNotification(any(), any(), any(), any()) } returns Unit

        val response = client.post("/webhooks/notifications/project-1") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "type": "INSERT",
                  "table": "notifications",
                  "record": {
                    "id": "notif-browser-1",
                    "type": "browser.activity.started",
                    "payload": {
                      "title": "Browser Activity Started",
                      "body": "Open the live browser viewer in SuprClaw.",
                      "taskId": "agent:main:main",
                      "browserSessionId": "browser_123",
                      "viewerUrl": "https://api.suprclaw.com/api/browser/sessions/browser_123/view",
                      "takeoverUrl": "https://api.suprclaw.com/api/browser/sessions/browser_123/takeover",
                      "browserState": "active",
                      "browserEventType": "browser.session.created"
                    }
                  },
                  "schema": "public",
                  "old_record": null
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            pushNotificationSender.sendNotification(
                fcmToken = "fcm-token",
                title = "Browser Activity Started",
                body = "Open the live browser viewer in SuprClaw.",
                data = match { data ->
                    data["notificationId"] == "notif-browser-1" &&
                        data["type"] == "browser.activity.started" &&
                        data["taskId"] == "agent:main:main" &&
                        data["browserSessionId"] == "browser_123" &&
                        data["viewerUrl"]?.contains("/view") == true &&
                        data["takeoverUrl"]?.contains("/takeover") == true &&
                        data["browserState"] == "active" &&
                        data["browserEventType"] == "browser.session.created"
                }
            )
        }
    }
}
