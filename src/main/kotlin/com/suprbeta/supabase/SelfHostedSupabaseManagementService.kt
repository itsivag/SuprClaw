package com.suprbeta.supabase

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

class SelfHostedSupabaseManagementService(
    @Suppress("unused") private val httpClient: HttpClient,
    private val application: Application
) : SupabaseManagementService {

    private val logger = application.log

    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private fun env(key: String): String =
        dotenv[key] ?: System.getenv(key) ?: ""

    private val selfHostedUrl: String = env("SUPABASE_SELF_HOSTED_URL")
        .ifBlank { throw IllegalStateException("SUPABASE_SELF_HOSTED_URL not found in environment") }

    private val serviceKey: String = env("SUPABASE_SELF_HOSTED_SERVICE_KEY")
        .ifBlank { throw IllegalStateException("SUPABASE_SELF_HOSTED_SERVICE_KEY not found in environment") }

    private val dbUrl: String = env("SUPABASE_SELF_HOSTED_DB_URL")
        .ifBlank { throw IllegalStateException("SUPABASE_SELF_HOSTED_DB_URL not found in environment") }

    private val sshHost: String = env("SUPABASE_SELF_HOSTED_SSH_HOST")
        .ifBlank { throw IllegalStateException("SUPABASE_SELF_HOSTED_SSH_HOST not found in environment") }

    private val sshUser: String = env("SUPABASE_SELF_HOSTED_SSH_USER").ifBlank { "root" }

    private val sshPassword: String = env("SUPABASE_SELF_HOSTED_SSH_PASSWORD")
        .ifBlank { throw IllegalStateException("SUPABASE_SELF_HOSTED_SSH_PASSWORD not found in environment") }

    private val dockerDir: String = env("SUPABASE_SELF_HOSTED_DOCKER_DIR").ifBlank { "/opt/supabase/docker" }

    override val webhookBaseUrl: String = env("WEBHOOK_BASE_URL").ifBlank { "https://api.suprclaw.com" }

    override val webhookSecret: String = env("WEBHOOK_SECRET")
        .ifBlank { throw IllegalStateException("WEBHOOK_SECRET not found in environment") }

    /** For self-hosted, the "management token" is the service_role JWT used as SUPABASE_ACCESS_TOKEN on the VPS. */
    override val managementToken: String get() = serviceKey

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Creates a new PostgreSQL schema `proj_<8hex>`, registers it with PostgREST,
     * and returns a [ProjectResult] where projectRef == schemaName.
     */
    override suspend fun createProject(name: String): ProjectResult {
        val schemaName = "proj_" + generateHex(8)
        logger.info("Creating self-hosted Supabase schema: $schemaName (for $name)")

        withContext(Dispatchers.IO) {
            executeJdbc("CREATE SCHEMA IF NOT EXISTS $schemaName")
        }

        addSchemaToPostgrest(schemaName)

        logger.info("✅ Self-hosted schema created: $schemaName endpoint=$selfHostedUrl")
        return ProjectResult(projectRef = schemaName, endpoint = selfHostedUrl)
    }

    /** No-op: schema creation via JDBC is synchronous. */
    override suspend fun waitForProjectActive(projectRef: String) {
        logger.info("Self-hosted mode: schema $projectRef is already active (no wait needed)")
    }

    /** Returns the fixed shared service_role key for all users. */
    override suspend fun getServiceKey(projectRef: String): String = serviceKey

    /**
     * Executes SQL via JDBC, substituting `public.` with `$projectRef.` throughout
     * so table references and foreign keys land in the correct schema.
     */
    override suspend fun runSql(projectRef: String, sql: String) {
        logger.info("Running SQL on self-hosted schema $projectRef (${sql.length} chars)")
        val adapted = sql.replace("public.", "$projectRef.")
        withContext(Dispatchers.IO) {
            executeJdbc(adapted)
        }
        logger.info("✅ SQL executed successfully on schema $projectRef")
    }

    /**
     * Creates the database webhook trigger in the given schema.
     * [runSql] handles `public.` → `$projectRef.` substitution automatically.
     */
    override suspend fun createDatabaseWebhook(projectRef: String) {
        val url = "$webhookBaseUrl/webhooks/tasks/$projectRef"
        val safeUrl = url.replace("'", "''")
        val safeSecret = webhookSecret.replace("'", "''")
        val headersJson = """{"Content-Type":"application/json","Authorization":"Bearer $safeSecret"}"""

        logger.info("Creating self-hosted database webhook for schema $projectRef → $url")

        runSql(projectRef, "CREATE EXTENSION IF NOT EXISTS pg_net SCHEMA $projectRef;")

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

        runSql(projectRef, """
            CREATE OR REPLACE TRIGGER "task-assignment-hook"
            AFTER INSERT ON public.task_assignees
            FOR EACH ROW EXECUTE FUNCTION public._suprclaw_task_assignment_notify();
        """.trimIndent())

        logger.info("✅ Self-hosted database webhook created for schema $projectRef")
    }

    /**
     * Drops the schema (CASCADE) and removes it from PostgREST's PGRST_DB_SCHEMAS.
     */
    override suspend fun deleteProject(projectRef: String) {
        logger.info("Dropping self-hosted schema $projectRef")
        withContext(Dispatchers.IO) {
            executeJdbc("DROP SCHEMA IF EXISTS $projectRef CASCADE")
        }
        removeSchemaFromPostgrest(projectRef)
        logger.info("🗑️ Self-hosted schema $projectRef dropped")
    }

    /** Returns the schema name (which is the projectRef itself). */
    override fun resolveSchema(projectRef: String): String = projectRef

    // ── PostgREST .env management via SSH ─────────────────────────────────

    private fun addSchemaToPostgrest(schemaName: String) {
        val d = "\$"  // literal $ for use inside triple-quoted shell commands
        val command = """
            cd $dockerDir && \
            CURRENT=${d}(grep '^PGRST_DB_SCHEMAS=' .env | cut -d= -f2) && \
            sed -i "s|^PGRST_DB_SCHEMAS=.*|PGRST_DB_SCHEMAS=${d}{CURRENT},$schemaName|" .env && \
            docker compose restart pgrst
        """.trimIndent()
        logger.info("Adding schema $schemaName to PostgREST and restarting pgrst")
        runSshCommandWithPassword(sshHost, sshUser, sshPassword, command)
        logger.info("✅ PostgREST restarted with schema $schemaName")
    }

    private fun removeSchemaFromPostgrest(schemaName: String) {
        val d = "\$"  // literal $ for use inside triple-quoted shell commands
        val command = """
            cd $dockerDir && \
            CURRENT=${d}(grep '^PGRST_DB_SCHEMAS=' .env | cut -d= -f2) && \
            NEW=${d}(echo "${d}CURRENT" | tr ',' '\n' | grep -v '^$schemaName${d}' | tr '\n' ',' | sed 's/,${d}//') && \
            sed -i "s|^PGRST_DB_SCHEMAS=.*|PGRST_DB_SCHEMAS=${d}NEW|" .env && \
            docker compose restart pgrst
        """.trimIndent()
        logger.info("Removing schema $schemaName from PostgREST and restarting pgrst")
        runSshCommandWithPassword(sshHost, sshUser, sshPassword, command)
        logger.info("✅ PostgREST restarted after removing schema $schemaName")
    }

    // ── JDBC helper ────────────────────────────────────────────────────────

    private fun executeJdbc(sql: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.createStatement().use { stmt ->
                // Execute each statement separated by semicolons individually
                // (JDBC drivers may not support multi-statement execution in one call)
                val statements = sql.split(";").map { it.trim() }.filter { it.isNotBlank() }
                for (statement in statements) {
                    stmt.execute(statement)
                }
            }
        }
    }

    // ── SSH password-auth helper ───────────────────────────────────────────

    private fun runSshCommandWithPassword(host: String, user: String, password: String, command: String): String {
        val ssh = SSHClient()
        return try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 10_000
            ssh.connect(host, 22)
            ssh.authPassword(user, password)

            val session = ssh.startSession()
            try {
                val cmd = session.exec(command)
                cmd.join(120, TimeUnit.SECONDS)

                val stdout = cmd.inputStream.bufferedReader().readText()
                val stderr = cmd.errorStream.bufferedReader().readText()
                val exitStatus = cmd.exitStatus

                logger.info("Self-hosted SSH exit=$exitStatus stdout=${stdout.take(200)}")
                if (stderr.isNotBlank()) {
                    logger.info("Self-hosted SSH stderr: ${stderr.take(200)}")
                }

                if (exitStatus != null && exitStatus != 0) {
                    throw RuntimeException("Self-hosted SSH command failed (exit=$exitStatus): ${stderr.take(500)}")
                }

                stdout
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun generateHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
