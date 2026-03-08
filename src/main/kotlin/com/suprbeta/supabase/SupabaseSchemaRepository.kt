package com.suprbeta.supabase

import io.ktor.server.application.*

private val USER_PROJECT_SQL = """
CREATE TABLE IF NOT EXISTS public.agents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL DEFAULT '',
    session_key TEXT NOT NULL DEFAULT '',
    is_lead BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'active',
    current_task UUID,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL DEFAULT '',
    description TEXT,
    status TEXT NOT NULL DEFAULT 'inbox',
    priority INT NOT NULL DEFAULT 5,
    created_by UUID REFERENCES public.agents(id),
    locked_by UUID REFERENCES public.agents(id),
    locked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    is_recurring BOOLEAN NOT NULL DEFAULT FALSE,
    cron_expression TEXT,
    recurrence_interval TEXT,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.task_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    from_agent UUID NOT NULL REFERENCES public.agents(id),
    content TEXT NOT NULL DEFAULT '',
    idempotency_key TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.task_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID REFERENCES public.tasks(id) ON DELETE CASCADE,
    created_by UUID REFERENCES public.agents(id),
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.task_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    from_status TEXT,
    to_status TEXT NOT NULL DEFAULT '',
    changed_by UUID REFERENCES public.agents(id),
    reason TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.task_assignees (
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ DEFAULT NOW(),
    assigned_by UUID REFERENCES public.agents(id),
    PRIMARY KEY (task_id, agent_id)
);

CREATE TABLE IF NOT EXISTS public.agent_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    task_id UUID REFERENCES public.tasks(id) ON DELETE SET NULL,
    action TEXT NOT NULL DEFAULT '',
    meta JSONB DEFAULT '{}',
    idempotency_key TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
""".trimIndent()

class SupabaseSchemaRepository(application: Application) {
    private val logger = application.log

    /**
     * Initialises a freshly-created per-user schema:
     *  1. Creates all tables via the management service.
     *  2. Creates the database webhook trigger.
     */
    suspend fun initializeUserProject(
        managementService: SupabaseManagementService,
        projectRef: String,
        userId: String,
        vpsGatewayUrl: String,
        gatewayToken: String
    ) {
        managementService.runSql(projectRef, USER_PROJECT_SQL)
        logger.info("✅ Tables created in schema $projectRef for userId=$userId")

        managementService.createDatabaseWebhook(projectRef)
        logger.info("✅ Database webhook created in schema $projectRef")

        // PostgREST serves agent/task operations immediately after provisioning, so its
        // schema cache must be refreshed after the JDBC DDL above creates the tables/triggers.
        managementService.reloadSchemaCache(projectRef)
        logger.info("✅ PostgREST schema cache reload requested for schema $projectRef")
    }

    /**
     * Cleans up a user's schema via the management service.
     */
    suspend fun cleanupUserProject(
        managementService: SupabaseManagementService,
        projectRef: String,
        userId: String
    ) {
        managementService.deleteProject(projectRef)
        logger.info("🗑️ Schema $projectRef deleted for userId=$userId")
    }
}
