package com.suprbeta.supabase

import com.suprbeta.core.SshHostKeyVerifierFactory
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.util.Base64
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

    private val dockerDir: String = env("SUPABASE_SELF_HOSTED_DOCKER_DIR").ifBlank { "/opt/supabase/docker" }
    private val sshHostKeyVerifier: HostKeyVerifier by lazy { SshHostKeyVerifierFactory.createSelfHostedVerifier(application) }

    private val privateKeyPem: String by lazy {
        val b64 = dotenv["PROVISIONING_SSH_PRIVATE_KEY_B64"]
            ?: System.getenv("PROVISIONING_SSH_PRIVATE_KEY_B64")
            ?: throw IllegalStateException("PROVISIONING_SSH_PRIVATE_KEY_B64 not found in environment")
        String(Base64.getDecoder().decode(b64))
    }

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
            // Grant PostgREST roles access to the new schema and all future tables in it
            executeJdbc("GRANT USAGE ON SCHEMA $schemaName TO anon, authenticated, service_role")
            executeJdbc("ALTER DEFAULT PRIVILEGES IN SCHEMA $schemaName GRANT ALL ON TABLES TO anon, authenticated, service_role")
            executeJdbc("ALTER DEFAULT PRIVILEGES IN SCHEMA $schemaName GRANT ALL ON SEQUENCES TO anon, authenticated, service_role")
            executeJdbc("ALTER DEFAULT PRIVILEGES IN SCHEMA $schemaName GRANT ALL ON FUNCTIONS TO anon, authenticated, service_role")
            // Create schema-scoped role for per-VPS MCP isolation
            for (sql in roleCreationStatements(schemaName)) {
                executeJdbc(sql)
            }
        }

        addSchemaToPostgrest(schemaName)

        logger.info("✅ Self-hosted schema created: $schemaName endpoint=$selfHostedUrl")
        return ProjectResult(projectRef = schemaName, endpoint = selfHostedUrl)
    }

    /** No-op: schema creation via JDBC is synchronous. */
    override suspend fun waitForProjectActive(projectRef: String) {
        logger.info("Self-hosted mode: schema $projectRef is already active (no wait needed)")
    }

    override suspend fun reloadSchemaCache(projectRef: String) {
        logger.info("Requesting PostgREST schema cache reload for schema $projectRef")
        withContext(Dispatchers.IO) {
            executeJdbc("NOTIFY pgrst, 'reload schema'")
            runSshCommand(sshHost, sshUser, buildSchemaCacheReloadCommand(dockerDir))
        }
        logger.info("Requested PostgREST schema cache reload for schema $projectRef")
    }

    /** Returns the fixed shared service_role key for all users. */
    override suspend fun getServiceKey(projectRef: String): String = serviceKey

    /**
     * Executes SQL via JDBC, substituting `public.` with `$projectRef.` throughout
     * so table references and foreign keys land in the correct schema.
     */
    override suspend fun runSql(projectRef: String, sql: String) {
        logger.info("Running SQL on self-hosted schema $projectRef (${sql.length} chars)")
        val adapted = SelfHostedSupabaseManagementService.adaptSqlForSchema(sql, projectRef)
        withContext(Dispatchers.IO) {
            executeJdbc(adapted)
        }
        logger.info("✅ SQL executed successfully on schema $projectRef")
    }

    companion object {
        /** Replaces every `public.` prefix with `$schema.` so SQL targeting the public schema is
         *  redirected to the per-user schema.  Exposed as `internal` for unit testing. */
        internal fun adaptSqlForSchema(sql: String, schema: String): String =
            sql.replace("public.", "$schema.")

        /** Returns the ordered list of SQL statements that create the schema-scoped MCP role. */
        internal fun roleCreationStatements(schemaName: String): List<String> = listOf(
            "CREATE ROLE ${schemaName}_rpc NOLOGIN",
            "GRANT USAGE ON SCHEMA $schemaName TO ${schemaName}_rpc",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA $schemaName TO ${schemaName}_rpc",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA $schemaName GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${schemaName}_rpc",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA $schemaName GRANT USAGE, SELECT ON SEQUENCES TO ${schemaName}_rpc",
            "ALTER ROLE ${schemaName}_rpc SET search_path = $schemaName",
            "GRANT ${schemaName}_rpc TO authenticator",
            "CREATE OR REPLACE FUNCTION ${schemaName}.execute_scoped_sql(query text) " +
                "RETURNS json LANGUAGE plpgsql SECURITY INVOKER AS \$\$ " +
                "DECLARE result json; q_upper text; " +
                "BEGIN " +
                "SET LOCAL search_path = ${schemaName}; " +
                "q_upper := upper(left(trim(query), 10)); " +
                "IF q_upper LIKE 'SELECT%' OR q_upper LIKE 'WITH%' THEN " +
                "EXECUTE 'SELECT json_agg(t) FROM (' || query || ') t' INTO result; " +
                "ELSIF position('RETURNING' IN upper(query)) > 0 THEN " +
                "EXECUTE 'WITH _q AS (' || query || ') SELECT json_agg(t) FROM _q t' INTO result; " +
                "ELSE " +
                "EXECUTE query; result := '[]'::json; " +
                "END IF; " +
                "RETURN COALESCE(result, '[]'::json); " +
                "END; \$\$",
            "GRANT EXECUTE ON FUNCTION ${schemaName}.execute_scoped_sql(text) TO ${schemaName}_rpc"
        )

        /** Returns the SQL statement that safely drops the schema-scoped MCP role. */
        internal fun roleDropStatement(schemaName: String): String =
            "DROP ROLE IF EXISTS ${schemaName}_rpc"

        internal fun buildSchemaCacheReloadCommand(dockerDir: String): String = """
            cd $dockerDir && \
            (docker compose kill -s SIGUSR1 rest || docker kill -s SIGUSR1 supabase-rest)
        """.trimIndent()

        /**
         * Normalises any supported DB URL variant to `jdbc:postgresql://…`.
         * Exposed as `internal` for unit testing.
         */
        internal fun normaliseJdbcUrl(dbUrl: String): String = when {
            dbUrl.startsWith("jdbc:postgresql://") -> dbUrl
            dbUrl.startsWith("jdbc:postgres://")   -> "jdbc:postgresql://" + dbUrl.removePrefix("jdbc:postgres://")
            dbUrl.startsWith("postgresql://")       -> "jdbc:$dbUrl"
            dbUrl.startsWith("postgres://")         -> "jdbc:postgresql://" + dbUrl.removePrefix("postgres://")
            else -> throw java.sql.SQLException(
                "SUPABASE_SELF_HOSTED_DB_URL must use postgresql:// or postgres:// scheme, got: ${dbUrl.take(40)}"
            )
        }
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

        val messagesUrl = "$webhookBaseUrl/webhooks/messages/$projectRef"
        val safeMessagesUrl = messagesUrl.replace("'", "''")

        runSql(projectRef, """
            CREATE OR REPLACE FUNCTION public._suprclaw_task_message_notify()
            RETURNS trigger
            LANGUAGE plpgsql
            AS '
            BEGIN
              PERFORM net.http_post(
                url := ''$safeMessagesUrl'',
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
            CREATE OR REPLACE TRIGGER "task-message-hook"
            AFTER INSERT ON public.task_messages
            FOR EACH ROW EXECUTE FUNCTION public._suprclaw_task_message_notify();
        """.trimIndent())

        val documentsUrl = "$webhookBaseUrl/webhooks/documents/$projectRef"
        val safeDocumentsUrl = documentsUrl.replace("'", "''")

        runSql(projectRef, """
            CREATE OR REPLACE FUNCTION public._suprclaw_task_document_notify()
            RETURNS trigger
            LANGUAGE plpgsql
            AS '
            BEGIN
              PERFORM net.http_post(
                url := ''$safeDocumentsUrl'',
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
            CREATE OR REPLACE TRIGGER "task-document-hook"
            AFTER INSERT ON public.task_documents
            FOR EACH ROW EXECUTE FUNCTION public._suprclaw_task_document_notify();
        """.trimIndent())

        logger.info("✅ Self-hosted database webhook created for schema $projectRef")
    }

    /**
     * Drops the schema (CASCADE) and removes it from PostgREST's PGRST_DB_SCHEMAS.
     */
    override suspend fun deleteProject(projectRef: String) {
        logger.info("Dropping self-hosted schema $projectRef")
        withContext(Dispatchers.IO) {
            // Revoke ALTER DEFAULT PRIVILEGES grants before dropping the role.
            //
            // Background: createProject runs ALTER DEFAULT PRIVILEGES IN SCHEMA $projectRef
            // GRANT ... TO ${projectRef}_rpc.  This writes pg_default_acl entries where postgres
            // is the grantor and ${projectRef}_rpc is the grantee.  DROP OWNED BY only removes
            // entries where the role is the *owner/grantor*, so it does NOT clean these up, and
            // DROP ROLE subsequently fails with "cannot be dropped because some objects depend on
            // it".  Explicit REVOKE removes the pg_default_acl rows so DROP ROLE can proceed.
            for (revoke in listOf(
                // Revoke authenticator's ability to SET ROLE to this scoped role.
                "REVOKE ${projectRef}_rpc FROM authenticator",
                // Revoke explicit grants on existing objects (tables/sequences already created).
                // ALTER DEFAULT PRIVILEGES REVOKE only removes pg_default_acl (future grants);
                // it does NOT remove grants already applied to existing tables/sequences.
                "REVOKE ALL ON ALL TABLES IN SCHEMA $projectRef FROM ${projectRef}_rpc",
                "REVOKE ALL ON ALL SEQUENCES IN SCHEMA $projectRef FROM ${projectRef}_rpc",
                "REVOKE USAGE ON SCHEMA $projectRef FROM ${projectRef}_rpc",
                // Revoke default privilege entries (pg_default_acl rows).
                "ALTER DEFAULT PRIVILEGES IN SCHEMA $projectRef REVOKE ALL ON TABLES FROM ${projectRef}_rpc",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA $projectRef REVOKE ALL ON SEQUENCES FROM ${projectRef}_rpc",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA $projectRef REVOKE ALL ON FUNCTIONS FROM ${projectRef}_rpc"
            )) {
                runCatching { executeJdbc(revoke) }
            }
            runCatching { executeJdbc("DROP FUNCTION IF EXISTS ${projectRef}.execute_scoped_sql(text)") }
            executeJdbc(roleDropStatement(projectRef))
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
            CURRENT=${d}{CURRENT:-public,storage,graphql_public} && \
            if grep -q '^PGRST_DB_SCHEMAS=' .env; then \
                sed -i "s|^PGRST_DB_SCHEMAS=.*|PGRST_DB_SCHEMAS=${d}{CURRENT},$schemaName|" .env; \
            else \
                echo "PGRST_DB_SCHEMAS=${d}{CURRENT},$schemaName" >> .env; \
            fi && \
            docker compose up -d --force-recreate rest
        """.trimIndent()
        logger.info("Adding schema $schemaName to PostgREST and restarting rest")
        runSshCommand(sshHost, sshUser, command)
        logger.info("✅ PostgREST restarted with schema $schemaName")
    }

    private fun removeSchemaFromPostgrest(schemaName: String) {
        val d = "\$"  // literal $ for use inside triple-quoted shell commands
        val command = """
            cd $dockerDir && \
            CURRENT=${d}(grep '^PGRST_DB_SCHEMAS=' .env | cut -d= -f2) && \
            CURRENT=${d}{CURRENT:-public,storage,graphql_public} && \
            NEW=${d}(echo "${d}CURRENT" | tr ',' '\n' | grep -v "^$schemaName${d}" | tr '\n' ',' | sed 's/,${d}//') && \
            if grep -q '^PGRST_DB_SCHEMAS=' .env; then \
                sed -i "s|^PGRST_DB_SCHEMAS=.*|PGRST_DB_SCHEMAS=${d}NEW|" .env; \
            else \
                echo "PGRST_DB_SCHEMAS=${d}NEW" >> .env; \
            fi && \
            docker compose up -d --force-recreate rest
        """.trimIndent()
        logger.info("Removing schema $schemaName from PostgREST and restarting rest")
        runSshCommand(sshHost, sshUser, command)
        logger.info("✅ PostgREST restarted after removing schema $schemaName")
    }

    // ── JDBC helper ────────────────────────────────────────────────────────

    private fun executeJdbc(sql: String) {
        // Normalise to jdbc:postgresql:// regardless of how the env var was set.
        // Supported input formats: postgresql://, postgres://, jdbc:postgresql://, jdbc:postgres://
        val normalised = normaliseJdbcUrl(dbUrl)

        // Extract credentials and pass them via Properties so that embedded user:password@
        // in the URL cannot confuse the JDBC driver's URL parser.
        // This is the root cause of "UnknownHostException: user:pass@host" failures.
        val (jdbcUrl, props) = splitCredentials(normalised)

        logger.info("JDBC connect → ${jdbcUrl.replace(Regex(":[^/][^/].*@"), ":***@")}") // mask password in logs

        // Bypass DriverManager (classloader issues in fat JARs) — use the driver directly.
        val driver = org.postgresql.Driver()
        val conn = driver.connect(jdbcUrl, props)
            ?: throw java.sql.SQLException("PostgreSQL driver returned null for URL: $jdbcUrl")
        conn.use {
            it.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    /**
     * Separates embedded user:password from a `jdbc:postgresql://user:pass@host/db` URL.
     * Returns a credential-free URL and a Properties object with `user` and `password` set.
     * If there are no embedded credentials the URL is returned unchanged with empty Properties.
     * Exposed as `internal` for unit testing.
     */
    internal fun splitCredentials(url: String): Pair<String, java.util.Properties> {
        val props = java.util.Properties()
        val prefix = "jdbc:postgresql://"
        if (!url.startsWith(prefix)) return url to props

        val rest = url.removePrefix(prefix)          // user:pass@host:port/db  (or just host:port/db)
        val atIdx = rest.indexOf('@')
        if (atIdx == -1) return url to props         // no credentials in URL

        val userInfo = rest.substring(0, atIdx)      // "user:pass"
        val hostPart = rest.substring(atIdx + 1)     // "host:port/db"

        val colonIdx = userInfo.indexOf(':')
        if (colonIdx == -1) {
            props.setProperty("user", userInfo)
        } else {
            props.setProperty("user", userInfo.substring(0, colonIdx))
            props.setProperty("password", userInfo.substring(colonIdx + 1))
        }

        return "$prefix$hostPart" to props
    }

    // ── SSH key-auth helper ────────────────────────────────────────────────

    private fun runSshCommand(host: String, user: String, command: String): String {
        val tempKey = File.createTempFile("suprclaw_supabase_key_", ".pem")
        return try {
            tempKey.writeText(privateKeyPem)
            tempKey.setReadable(false, false)
            tempKey.setReadable(true, true)

            val ssh = SSHClient()
            try {
                ssh.addHostKeyVerifier(sshHostKeyVerifier)
                ssh.connectTimeout = 10_000
                ssh.connect(host, 22)
                ssh.authPublickey(user, ssh.loadKeys(tempKey.absolutePath))

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
        } finally {
            tempKey.delete()
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun generateHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
