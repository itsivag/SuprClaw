package com.suprbeta.supabase

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val MANAGEMENT_API_BASE = "https://api.supabase.com/v1"

@Serializable
data class CreateProjectRequest(
    val name: String,
    @SerialName("organization_id") val organizationId: String,
    val region: String,
    val plan: String = "free",
    @SerialName("db_pass") val dbPass: String
)

@Serializable
data class ProjectResult(
    val projectRef: String,
    val endpoint: String
)

class SupabaseManagementService(
    private val httpClient: HttpClient,
    private val application: Application
) {
    private val logger = application.log
    private val json = Json { ignoreUnknownKeys = true }

    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    val managementToken: String = dotenv["SUPABASE_MANAGEMENT_TOKEN"]
        ?: throw IllegalStateException("SUPABASE_MANAGEMENT_TOKEN not found in environment")

    private val orgId: String = dotenv["SUPABASE_ORG_ID"]
        ?: throw IllegalStateException("SUPABASE_ORG_ID not found in environment")

    private val region: String = dotenv["SUPABASE_REGION"] ?: "us-east-1"

    val webhookBaseUrl: String = dotenv["WEBHOOK_BASE_URL"] ?: "https://api.suprclaw.com"

    val webhookSecret: String = dotenv["WEBHOOK_SECRET"]
        ?: throw IllegalStateException("WEBHOOK_SECRET not found in environment")

    /** Creates a new Supabase project under the organization. */
    suspend fun createProject(name: String): ProjectResult {
        logger.info("Creating Supabase project: $name")

        val dbPass = generateDbPassword()

        val response: HttpResponse = httpClient.post("$MANAGEMENT_API_BASE/projects") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $managementToken")
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("organization_id", orgId)
                    put("region", region)
                    put("plan", "free")
                    put("db_pass", dbPass)
                }.toString()
            )
        }

        val body = response.bodyAsText()
        logger.info("Supabase createProject response: ${response.status}")

        val jsonBody = json.parseToJsonElement(body).jsonObject
        val projectRef = jsonBody["id"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Supabase project creation did not return id. Response: $body")
        val endpoint = jsonBody["endpoint"]?.jsonPrimitive?.content
            ?: "https://$projectRef.supabase.co"

        logger.info("✅ Supabase project created: ref=$projectRef endpoint=$endpoint")
        return ProjectResult(projectRef = projectRef, endpoint = endpoint)
    }

    /** Polls until the project status is ACTIVE_HEALTHY. Timeout: 3 minutes. */
    suspend fun waitForProjectActive(projectRef: String) {
        logger.info("Waiting for Supabase project $projectRef to become active...")
        val deadline = System.currentTimeMillis() + 180_000L

        while (System.currentTimeMillis() < deadline) {
            try {
                val response: HttpResponse = httpClient.get("$MANAGEMENT_API_BASE/projects/$projectRef") {
                    header("Authorization", "Bearer $managementToken")
                }
                val body = response.bodyAsText()
                val status = json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content

                logger.info("Supabase project $projectRef status: $status")

                if (status == "ACTIVE_HEALTHY") {
                    logger.info("✅ Supabase project $projectRef is ACTIVE_HEALTHY")
                    return
                }
            } catch (e: Exception) {
                logger.warn("Error polling Supabase project $projectRef: ${e.message}")
            }

            delay(5_000L)
        }

        throw IllegalStateException("Supabase project $projectRef did not become active within 3 minutes")
    }

    /** Fetches the service_role API key for the project. */
    suspend fun getServiceKey(projectRef: String): String {
        logger.info("Fetching service key for project $projectRef")

        val response: HttpResponse = httpClient.get("$MANAGEMENT_API_BASE/projects/$projectRef/api-keys") {
            header("Authorization", "Bearer $managementToken")
        }

        val body = response.bodyAsText()
        val keys = json.parseToJsonElement(body).jsonArray

        val serviceKey = keys
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content == "service_role" }
            ?.get("api_key")?.jsonPrimitive?.content
            ?: throw IllegalStateException("service_role key not found for project $projectRef. Response: $body")

        logger.info("✅ Service key retrieved for project $projectRef")
        return serviceKey
    }

    /** Executes SQL against the project's database via the Management API. */
    suspend fun runSql(projectRef: String, sql: String) {
        logger.info("Running SQL on project $projectRef (${sql.length} chars)")

        val response: HttpResponse = httpClient.post("$MANAGEMENT_API_BASE/projects/$projectRef/database/query") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $managementToken")
            setBody(buildJsonObject { put("query", sql) }.toString())
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("SQL execution failed on project $projectRef: ${response.status} — $body")
        }

        logger.info("✅ SQL executed successfully on project $projectRef")
    }

    /**
     * Creates a database webhook trigger on the task_assignees table of the given project.
     * Uses pg_net directly (always available) rather than supabase_functions.http_request
     * (only present on dashboard-created projects).
     * The webhook fires on INSERT and POSTs to the backend at /webhooks/tasks/{projectRef}.
     */
    suspend fun createDatabaseWebhook(projectRef: String) {
        val url = "$webhookBaseUrl/webhooks/tasks/$projectRef"
        // Escape single quotes for use inside single-quoted SQL strings
        val safeUrl = url.replace("'", "''")
        val safeSecret = webhookSecret.replace("'", "''")
        val headersJson = """{"Content-Type":"application/json","Authorization":"Bearer $safeSecret"}"""

        logger.info("Creating database webhook for project $projectRef → $url")

        // 1. Ensure pg_net is enabled
        runSql(projectRef, "CREATE EXTENSION IF NOT EXISTS pg_net;")

        // 2. Create the trigger function.
        //    Function body uses single-quoted string ('' = escaped single quote inside).
        runSql(projectRef, """
            CREATE OR REPLACE FUNCTION public._suprclaw_task_assignment_notify()
            RETURNS trigger
            LANGUAGE plpgsql
            AS '
            BEGIN
              PERFORM net.http_post(
                url := ''$safeUrl'',
                body := jsonb_build_object(
                  ''type'', ''INSERT'',
                  ''table'', TG_TABLE_NAME,
                  ''record'', to_jsonb(NEW),
                  ''schema'', TG_TABLE_SCHEMA,
                  ''old_record'', NULL::jsonb
                ),
                headers := ''$headersJson''::jsonb,
                timeout_milliseconds := 5000
              );
              RETURN NEW;
            END;
            ';
        """.trimIndent())

        // 3. Attach the trigger (CREATE OR REPLACE TRIGGER requires PG 14+; Supabase uses PG 15)
        runSql(projectRef, """
            CREATE OR REPLACE TRIGGER "task-assignment-hook"
            AFTER INSERT ON public.task_assignees
            FOR EACH ROW EXECUTE FUNCTION public._suprclaw_task_assignment_notify();
        """.trimIndent())

        logger.info("✅ Database webhook created for project $projectRef")
    }

    /** Deletes a Supabase project. */
    suspend fun deleteProject(projectRef: String) {
        logger.info("Deleting Supabase project $projectRef")

        httpClient.delete("$MANAGEMENT_API_BASE/projects/$projectRef") {
            header("Authorization", "Bearer $managementToken")
        }

        logger.info("🗑️ Supabase project $projectRef deleted")
    }

    private fun generateDbPassword(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#\$%"
        return (1..32).map { chars.random() }.joinToString("")
    }
}
