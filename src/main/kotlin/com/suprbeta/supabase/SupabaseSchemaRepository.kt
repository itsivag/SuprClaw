package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.server.application.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    updated_at TIMESTAMPTZ DEFAULT NOW()
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

class SupabaseSchemaRepository(
    private val supabase: SupabaseClient,
    application: Application
) {
    private val logger = application.log

    /**
     * Initialises a freshly-created per-user Supabase project:
     *  1. Creates all tables in the project's public schema via Management API SQL.
     *  2. Registers the user's gateway in the central project's user_droplets table.
     */
    suspend fun initializeUserProject(
        managementService: SupabaseManagementService,
        projectRef: String,
        userId: String,
        vpsGatewayUrl: String,
        gatewayToken: String
    ) {
        managementService.runSql(projectRef, USER_PROJECT_SQL)
        logger.info("✅ Tables created in Supabase project $projectRef for userId=$userId")

        val schemaName = schemaName(userId)
        supabase.postgrest.rpc("insert_user_droplet", buildJsonObject {
            put("p_schema_name", schemaName)
            put("p_vps_gateway_url", vpsGatewayUrl)
            put("p_gateway_token", gatewayToken)
        })
        logger.info("✅ Registered droplet in central user_droplets: schema=$schemaName for userId=$userId")
    }

    /**
     * Cleans up a user's Supabase project:
     *  1. Deletes the per-user Supabase project via Management API.
     *  2. Removes the user's gateway row from the central project's user_droplets table.
     */
    suspend fun cleanupUserProject(
        managementService: SupabaseManagementService,
        projectRef: String,
        userId: String
    ) {
        managementService.deleteProject(projectRef)
        logger.info("🗑️ Supabase project $projectRef deleted for userId=$userId")

        val schemaName = schemaName(userId)
        supabase.postgrest.rpc("delete_user_droplet", buildJsonObject {
            put("p_schema_name", schemaName)
        })
        logger.info("🗑️ Removed user_droplets row for schema=$schemaName userId=$userId")
    }

    private fun schemaName(userId: String) = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
}
