package com.suprbeta.supabase

import com.suprbeta.firebase.FcmNotificationService
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.PushNotificationSender
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.suprbeta.runtime.RuntimeWakeDispatcher

fun Application.configureWebhookRoutes(
    firestoreRepository: FirestoreRepository,
    userClientProvider: UserSupabaseClientProvider,
    agentRepository: SupabaseAgentRepository,
    webhookSecret: String,
    wakeDispatcher: RuntimeWakeDispatcher
) = configureWebhookRoutes(
    firestoreRepository = firestoreRepository,
    userClientProvider = userClientProvider,
    agentRepository = agentRepository,
    webhookSecret = webhookSecret,
    wakeDispatcher = wakeDispatcher,
    pushNotificationSender = FcmNotificationService(this)
)

internal fun Application.configureWebhookRoutes(
    firestoreRepository: FirestoreRepository,
    userClientProvider: UserSupabaseClientProvider,
    agentRepository: SupabaseAgentRepository,
    webhookSecret: String,
    wakeDispatcher: RuntimeWakeDispatcher,
    pushNotificationSender: PushNotificationSender
) {
    val json = Json { ignoreUnknownKeys = true }

    routing {
        /**
         * POST /webhooks/tasks/{projectRef}
         *
         * Receives Supabase database webhook events from the per-user project's
         * task_assignees table. Validates the shared secret, resolves the user's
         * droplet, fetches the agent's session_key, and forwards a task.assigned
         * notification to the VPS gateway.
         *
         * Expected body (Supabase webhook format):
         * {
         *   "type": "INSERT",
         *   "table": "task_assignees",
         *   "record": { "task_id": "uuid", "agent_id": "uuid", ... },
         *   "schema": "public",
         *   "old_record": null
         * }
         */
        post("/webhooks/tasks/{projectRef}") {
            val projectRef = call.parameters["projectRef"]
            if (projectRef.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectRef"))
                return@post
            }

            // Validate shared secret
            val authHeader = call.request.header(HttpHeaders.Authorization) ?: ""
            val expectedToken = "Bearer $webhookSecret"
            if (authHeader != expectedToken) {
                log.warn("Webhook rejected: invalid auth header for projectRef=$projectRef")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return@post
            }

            val body = call.receiveText()
            log.info("Webhook received for projectRef=$projectRef body=${body.take(300)}")

            // Parse Supabase webhook payload
            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed == null) {
                log.warn("Webhook ignored: failed to parse body for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val record = parsed["record"]?.jsonObject
            val taskId = record?.get("task_id")?.jsonPrimitive?.content
            val agentId = record?.get("agent_id")?.jsonPrimitive?.content

            if (taskId.isNullOrBlank() || agentId.isNullOrBlank()) {
                log.warn("Webhook ignored: missing task_id or agent_id for projectRef=$projectRef record=$record")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            log.info("Webhook processing task=$taskId agent=$agentId projectRef=$projectRef")

            // Resolve user droplet by projectRef
            val droplet = firestoreRepository.getUserDropletInternalByProjectRef(projectRef)
            if (droplet == null) {
                log.warn("Webhook: no droplet found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Get agent session_key from the user's Supabase project
            val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
            val agent = agentRepository.getAgentById(client, agentId)
            if (agent == null) {
                log.warn("Webhook: agent $agentId not found in project $projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Derive the runtime agent ID from session_key (format: "agent:{agentId}:...").
            // Using agent.name would break for the lead agent ("Lead" in DB vs "main" in runtime config).
            val runtimeAgentId = agent.sessionKey.split(":").getOrNull(1)?.ifBlank { null } ?: agent.name
            log.info("Webhook resolved agent=${agent.name} runtimeAgentId=$runtimeAgentId sessionKey=${agent.sessionKey} vpsUrl=${droplet.vpsGatewayUrl}")

            // Dispatch the task assignment through the runtime wake bridge.
            val hookSessionKey = "hook:task:$taskId"
            try {
                wakeDispatcher.dispatch(
                    droplet = droplet,
                    agentId = runtimeAgentId,
                    sessionKey = hookSessionKey,
                    message = "Task $taskId has been assigned to you"
                )
                log.info("Webhook forwarded task=$taskId agent=${agent.name} runtimeAgentId=$runtimeAgentId sessionKey=$hookSessionKey")
            } catch (e: Exception) {
                log.error("Webhook: failed to dispatch runtime wake for task=$taskId agent=${agent.name}", e)
            }

            call.respond(HttpStatusCode.OK)
        }

        /**
         * POST /webhooks/messages/{projectRef}
         *
         * Fires on every task_messages INSERT. Always routes to the lead agent so it
         * can observe and react to messages sent by sub-agents.
         *
         * Expected body (Supabase webhook format):
         * {
         *   "type": "INSERT",
         *   "table": "task_messages",
         *   "record": { "id": "uuid", "task_id": "uuid", "from_agent": "uuid", "content": "..." },
         *   "schema": "public",
         *   "old_record": null
         * }
         */
        post("/webhooks/messages/{projectRef}") {
            val projectRef = call.parameters["projectRef"]
            if (projectRef.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectRef"))
                return@post
            }

            val authHeader = call.request.header(HttpHeaders.Authorization) ?: ""
            if (authHeader != "Bearer $webhookSecret") {
                log.warn("Message webhook rejected: invalid auth for projectRef=$projectRef")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return@post
            }

            val body = call.receiveText()
            log.info("Message webhook received for projectRef=$projectRef body=${body.take(300)}")

            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed == null) {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val record = parsed["record"]?.jsonObject
            val messageId = record?.get("id")?.jsonPrimitive?.content
            val taskId = record?.get("task_id")?.jsonPrimitive?.content
            val fromAgentId = record?.get("from_agent")?.jsonPrimitive?.content
            val content = record?.get("content")?.jsonPrimitive?.content

            if (taskId.isNullOrBlank() || messageId.isNullOrBlank()) {
                log.warn("Message webhook ignored: missing task_id or id for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val droplet = firestoreRepository.getUserDropletInternalByProjectRef(projectRef)
            if (droplet == null) {
                log.warn("Message webhook: no droplet found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
            val leadAgent = agentRepository.getLeadAgent(client)
            if (leadAgent == null) {
                log.warn("Message webhook: no lead agent found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Skip if the message was sent by the lead agent itself to avoid loops
            val leadAgentUuid = leadAgent.id
            if (!leadAgentUuid.isNullOrBlank() && leadAgentUuid == fromAgentId) {
                log.info("Message webhook: skipping message from lead agent itself task=$taskId")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val runtimeAgentId = leadAgent.sessionKey.split(":").getOrNull(1)?.ifBlank { null } ?: leadAgent.name
            log.info("Message webhook routing to lead=${leadAgent.name} runtimeAgentId=$runtimeAgentId task=$taskId message=$messageId")

            val hookSessionKey = "hook:task:$taskId"
            val preview = content?.take(120)?.replace("\"", "\\\"") ?: ""
            try {
                wakeDispatcher.dispatch(
                    droplet = droplet,
                    agentId = runtimeAgentId,
                    sessionKey = hookSessionKey,
                    message = "New message on task $taskId: $preview"
                )
                log.info("Message webhook forwarded task=$taskId message=$messageId to lead runtimeAgentId=$runtimeAgentId")
            } catch (e: Exception) {
                log.error("Message webhook: failed to dispatch runtime wake for task=$taskId message=$messageId", e)
            }

            // Send FCM push notification to the user
            val fcmToken = firestoreRepository.getFcmToken(droplet.userId)
            if (!fcmToken.isNullOrBlank()) {
                pushNotificationSender.sendNotification(
                    fcmToken = fcmToken,
                    title = "New Message",
                    body = if (preview.isNotBlank()) preview else "You have a new message on your task.",
                    data = mapOf("taskId" to taskId, "messageId" to messageId)
                )
            } else {
                log.debug("No FCM token for userId=${droplet.userId}, skipping push notification")
            }

            call.respond(HttpStatusCode.OK)
        }

        /**
         * POST /webhooks/documents/{projectRef}
         *
         * Fires on every task_documents INSERT. Always routes to the lead agent.
         *
         * Expected body (Supabase webhook format):
         * {
         *   "type": "INSERT",
         *   "table": "task_documents",
         *   "record": { "id": "uuid", "task_id": "uuid", "created_by": "uuid", "title": "...", "content": "..." },
         *   "schema": "public",
         *   "old_record": null
         * }
         */
        post("/webhooks/documents/{projectRef}") {
            val projectRef = call.parameters["projectRef"]
            if (projectRef.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectRef"))
                return@post
            }

            val authHeader = call.request.header(HttpHeaders.Authorization) ?: ""
            if (authHeader != "Bearer $webhookSecret") {
                log.warn("Document webhook rejected: invalid auth for projectRef=$projectRef")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return@post
            }

            val body = call.receiveText()
            log.info("Document webhook received for projectRef=$projectRef body=${body.take(300)}")

            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed == null) {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val record = parsed["record"]?.jsonObject
            val documentId = record?.get("id")?.jsonPrimitive?.content
            val taskId = record?.get("task_id")?.jsonPrimitive?.content
            val createdBy = record?.get("created_by")?.jsonPrimitive?.content
            val title = record?.get("title")?.jsonPrimitive?.content

            if (taskId.isNullOrBlank() || documentId.isNullOrBlank()) {
                log.warn("Document webhook ignored: missing task_id or id for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val droplet = firestoreRepository.getUserDropletInternalByProjectRef(projectRef)
            if (droplet == null) {
                log.warn("Document webhook: no droplet found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
            val leadAgent = agentRepository.getLeadAgent(client)
            if (leadAgent == null) {
                log.warn("Document webhook: no lead agent found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Skip if the document was created by the lead agent itself to avoid loops
            if (!leadAgent.id.isNullOrBlank() && leadAgent.id == createdBy) {
                log.info("Document webhook: skipping document created by lead agent itself task=$taskId")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val runtimeAgentId = leadAgent.sessionKey.split(":").getOrNull(1)?.ifBlank { null } ?: leadAgent.name
            log.info("Document webhook routing to lead=${leadAgent.name} runtimeAgentId=$runtimeAgentId task=$taskId document=$documentId")

            val hookSessionKey = "hook:task:$taskId"
            val safeTitle = title?.replace("\"", "\\\"") ?: ""
            try {
                wakeDispatcher.dispatch(
                    droplet = droplet,
                    agentId = runtimeAgentId,
                    sessionKey = hookSessionKey,
                    message = "New document on task $taskId: $safeTitle"
                )
                log.info("Document webhook forwarded task=$taskId document=$documentId to lead runtimeAgentId=$runtimeAgentId")
            } catch (e: Exception) {
                log.error("Document webhook: failed to dispatch runtime wake for task=$taskId document=$documentId", e)
            }

            // Send FCM push notification to the user
            val fcmToken = firestoreRepository.getFcmToken(droplet.userId)
            if (!fcmToken.isNullOrBlank()) {
                pushNotificationSender.sendNotification(
                    fcmToken = fcmToken,
                    title = if (!title.isNullOrBlank()) "📄 $title" else "New Document",
                    body = "An agent created a new document for your task.",
                    data = mapOf("taskId" to taskId, "documentId" to documentId)
                )
            } else {
                log.debug("No FCM token for userId=${droplet.userId}, skipping push notification")
            }

            call.respond(HttpStatusCode.OK)
        }

        /**
         * POST /webhooks/notifications/{projectRef}
         *
         * Fires on every notifications INSERT and forwards the inserted notification
         * to the user's registered FCM token, if any.
         */
        post("/webhooks/notifications/{projectRef}") {
            val projectRef = call.parameters["projectRef"]
            if (projectRef.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectRef"))
                return@post
            }

            val authHeader = call.request.header(HttpHeaders.Authorization) ?: ""
            if (authHeader != "Bearer $webhookSecret") {
                log.warn("Notification webhook rejected: invalid auth for projectRef=$projectRef")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return@post
            }

            val body = call.receiveText()
            log.info("Notification webhook received for projectRef=$projectRef body=${body.take(300)}")

            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed == null) {
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val record = parsed["record"]?.jsonObject
            val notificationId = record?.get("id")?.jsonPrimitive?.contentOrNull
            val notificationType = record?.get("type")?.jsonPrimitive?.contentOrNull
            val payload = record?.get("payload")

            if (notificationId.isNullOrBlank()) {
                log.warn("Notification webhook ignored: missing notification id for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val droplet = firestoreRepository.getUserDropletInternalByProjectRef(projectRef)
            if (droplet == null) {
                log.warn("Notification webhook: no droplet found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val fcmToken = firestoreRepository.getFcmToken(droplet.userId)
            if (fcmToken.isNullOrBlank()) {
                log.debug("No FCM token for userId=${droplet.userId}, skipping notification push")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val payloadObject = payload as? JsonObject
            val browserEventType = payloadObject?.stringValue("browserEventType")
                ?: payloadObject?.stringValue("type")
            if (
                notificationType == "browser.activity.started" ||
                browserEventType == "browser.session.created" ||
                notificationType == "browser.takeover.requested" ||
                browserEventType == "browser.session.takeover_requested"
            ) {
                log.info("Notification webhook skipping FCM push for browser activity notificationId=$notificationId")
                call.respond(HttpStatusCode.OK)
                return@post
            }
            val title = payloadObject?.get("title")?.jsonPrimitive?.contentOrNull
                ?: notificationType?.toDisplayNotificationTitle()
                ?: "New Notification"
            val notificationBody = payloadObject?.get("body")?.jsonPrimitive?.contentOrNull
                ?: payloadObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: payload.toNotificationBody()
                ?: "You have a new notification."
            val data = buildMap {
                put("notificationId", notificationId)
                notificationType?.let { put("type", it) }
                payloadObject?.stringValue("taskId")?.let { put("taskId", it) }
                payloadObject?.stringValue("task_id")?.let { put("taskId", it) }
                payloadObject?.stringValue("browserSessionId")?.let { put("browserSessionId", it) }
                payloadObject?.stringValue("sessionId")?.let { put("browserSessionId", it) }
                payloadObject?.stringValue("viewerUrl")?.let { put("viewerUrl", it) }
                payloadObject?.stringValue("takeoverUrl")?.let { put("takeoverUrl", it) }
                payloadObject?.stringValue("browserState")?.let { put("browserState", it) }
                payloadObject?.stringValue("state")?.let { put("browserState", it) }
                payloadObject?.stringValue("browserEventType")?.let { put("browserEventType", it) }
                payloadObject?.stringValue("type")?.let { put("browserEventType", it) }
                payloadObject?.stringValue("messageId")?.let { put("messageId", it) }
                payloadObject?.stringValue("message_id")?.let { put("messageId", it) }
                payloadObject?.stringValue("documentId")?.let { put("documentId", it) }
                payloadObject?.stringValue("document_id")?.let { put("documentId", it) }
            }

            pushNotificationSender.sendNotification(
                fcmToken = fcmToken,
                title = title,
                body = notificationBody,
                data = data
            )

            call.respond(HttpStatusCode.OK)
        }
    }
}

private fun String.toDisplayNotificationTitle(): String =
    split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        .ifBlank { "New Notification" }

private fun JsonObject.stringValue(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun kotlinx.serialization.json.JsonElement?.toNotificationBody(): String? {
    val element = this ?: return null
    val text = when (element) {
        is JsonObject -> element.toString()
        else -> element.jsonPrimitive.contentOrNull ?: element.toString()
    }
    return text
        .trim()
        .takeIf { it.isNotBlank() }
        ?.take(160)
}
