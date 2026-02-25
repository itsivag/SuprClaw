package com.suprbeta.supabase

import com.suprbeta.firebase.FirestoreRepository
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Application.configureWebhookRoutes(
    firestoreRepository: FirestoreRepository,
    userClientProvider: UserSupabaseClientProvider,
    agentRepository: SupabaseAgentRepository,
    httpClient: HttpClient,
    webhookSecret: String
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
            log.debug("Webhook received for projectRef=$projectRef: ${body.take(300)}")

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
                log.warn("Webhook ignored: missing task_id or agent_id for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Resolve user droplet by projectRef
            val droplet = firestoreRepository.getUserDropletInternalByProjectRef(projectRef)
            if (droplet == null) {
                log.warn("Webhook: no droplet found for projectRef=$projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Get agent session_key from the user's Supabase project
            val supabaseUrl = "https://${droplet.supabaseProjectRef}.supabase.co"
            val client = userClientProvider.getClient(supabaseUrl, droplet.supabaseServiceKey)
            val agent = agentRepository.getAgentById(client, agentId)
            if (agent == null) {
                log.warn("Webhook: agent $agentId not found in project $projectRef")
                call.respond(HttpStatusCode.OK)
                return@post
            }

            // Forward task assignment notification to VPS gateway
            val notifyUrl = "${droplet.vpsGatewayUrl}/api/task-assigned"
            try {
                val response: HttpResponse = httpClient.post(notifyUrl) {
                    header(HttpHeaders.Authorization, "Bearer ${droplet.gatewayToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"task_id":"$taskId","agent_id":"$agentId","session_key":"${agent.sessionKey}"}""")
                }
                log.info("Webhook forwarded task=$taskId agent=$agentId to VPS (status=${response.status})")
            } catch (e: Exception) {
                log.error("Webhook: failed to notify VPS gateway for task=$taskId agent=$agentId", e)
            }

            call.respond(HttpStatusCode.OK)
        }
    }
}
