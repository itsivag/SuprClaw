package com.suprbeta.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for [SelfHostedSupabaseManagementService.adaptSqlForSchema].
 *
 * This logic redirects SQL written for `public.` into the per-user schema.
 * A regression here silently puts all data in the wrong schema, so we test it exhaustively.
 */
class SelfHostedSqlAdaptationTest {

    private fun adapt(sql: String, schema: String) =
        SelfHostedSupabaseManagementService.adaptSqlForSchema(sql, schema)

    @Test
    fun `simple table reference is rewritten`() {
        assertEquals(
            "SELECT * FROM proj_abc.agents",
            adapt("SELECT * FROM public.agents", "proj_abc")
        )
    }

    @Test
    fun `CREATE TABLE statement is rewritten`() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS proj_abc.tasks (id UUID PRIMARY KEY)",
            adapt("CREATE TABLE IF NOT EXISTS public.tasks (id UUID PRIMARY KEY)", "proj_abc")
        )
    }

    @Test
    fun `REFERENCES clause is rewritten`() {
        assertEquals(
            "task_id UUID NOT NULL REFERENCES proj_abc.tasks(id) ON DELETE CASCADE",
            adapt("task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE", "proj_abc")
        )
    }

    @Test
    fun `multiple occurrences are all replaced`() {
        val sql = """
            CREATE TABLE public.task_messages (
                task_id UUID REFERENCES public.tasks(id),
                agent_id UUID REFERENCES public.agents(id)
            )
        """.trimIndent()
        val adapted = adapt(sql, "proj_xyz")
        assertFalse("public." in adapted, "All public. occurrences should be replaced")
        assertEquals(3, "proj_xyz\\.".toRegex().findAll(adapted).count(),
            "Expected exactly 3 replacements")
    }

    @Test
    fun `sql with no public prefix is unchanged`() {
        val sql = "SELECT gen_random_uuid()"
        assertEquals(sql, adapt(sql, "proj_abc"))
    }

    @Test
    fun `function call on public schema is rewritten`() {
        assertEquals(
            "EXECUTE FUNCTION proj_abc._suprclaw_task_assignment_notify()",
            adapt("EXECUTE FUNCTION public._suprclaw_task_assignment_notify()", "proj_abc")
        )
    }

    @Test
    fun `identity transform when schema is public`() {
        val sql = "CREATE TABLE public.agents (id UUID PRIMARY KEY)"
        // Replacing "public." with "public." → no change
        assertEquals(sql, adapt(sql, "public"))
    }

    @Test
    fun `trigger ON clause is rewritten`() {
        assertEquals(
            "AFTER INSERT ON proj_hello.task_assignees FOR EACH ROW",
            adapt("AFTER INSERT ON public.task_assignees FOR EACH ROW", "proj_hello")
        )
    }

    @Test
    fun `word public without dot is not changed`() {
        // "public" as a bare word (no trailing dot) must not be touched
        val sql = "GRANT USAGE ON SCHEMA public TO anon"
        assertEquals(sql, adapt(sql, "proj_abc"))
    }
}
