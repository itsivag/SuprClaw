package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import java.nio.charset.StandardCharsets
import java.util.Base64

object AgentWorkspaceBootstrap {
    private const val AGENTS_MARKER = "SUPRCLAW_SKILL_BOOTSTRAP_V2"
    private const val LEGACY_TOOLS_MARKER = "SUPRCLAW_LEGACY_TOOLS_MIRROR_V2"

    private val agentsGuidance = """
        ## SuprClaw Runtime Skills
        <!-- $AGENTS_MARKER -->
        Before coordinating work, load the SuprClaw workspace skills in `skills/`.
        - `skills/suprclaw-supabase/SKILL.md` is the canonical source for SQL/RPC coordination rules.
        - `skills/suprclaw-cloud-browser/SKILL.md` defines the required cloud browser workflow.
        - `skills/suprclaw-marketplace/SKILL.md` defines marketplace install and workspace conventions.
        - For lead coordination, keep using RPC calls through `execute_sql`; never write mission-control tables directly.
    """.trimIndent()

    private val legacyToolsMirror = """
        ## SuprClaw Legacy Mirror
        <!-- $LEGACY_TOOLS_MARKER -->
        The canonical lead coordination rules now live in `AGENTS.md` and `skills/`.
        Keep this file only as a temporary compatibility mirror. Prefer skill-first guidance when both exist.

        ### Cloud Browser
        Use the SuprClaw cloud browser tools for interactive browsing and authenticated sessions.
        - Start interactive browser work with `cloud_browser_open`.
        - Use `cloud_browser_exec` for navigation, clicks, form fills, and inspection.
        - Use `cloud_browser_request_takeover` for CAPTCHA, MFA, payments, and destructive actions.
        - Use `cloud_browser_resume` after user takeover and `cloud_browser_close` when done.
    """.trimIndent()

    private val supabaseSkill = """
        # SuprClaw Supabase Coordination

        Mission-control coordination is SQL over `execute_sql`.

        Rules:
        - Query before acting.
        - Use RPC functions instead of direct table writes.
        - Log every meaningful coordination action.
        - Read full context before closing or reassigning work.

        Core RPC patterns:
        - `SELECT * FROM lead_get_inbox();`
        - `SELECT lead_assign_task('<task_id>', '<agent_id>', '<lead_id>');`
        - `SELECT * FROM lead_get_review_tasks('<lead_id>');`
        - `SELECT lead_close_task('<task_id>', '<lead_id>');`
        - `SELECT * FROM lead_get_stalled_tasks(45);`
        - `SELECT agent_get_task_context('<task_id>');`
        - `SELECT agent_post_message('<task_id>', '<agent_id>', '<content>', '<idempotency_key>');`
        - `SELECT agent_transition_task('<task_id>', '<from_status>', '<to_status>', '<agent_id>', '<reason>');`
        - `SELECT agent_log_action('<agent_id>', NULL, '<action>', '<meta_json>', '<idempotency_key>');`
        - `SELECT * FROM agent_get_my_notifications('<agent_id>');`
        - `SELECT agent_ack_notifications('<agent_id>');`
        - `SELECT agent_update_status('<agent_id>', 'active');`
    """.trimIndent()

    private val cloudBrowserSkill = """
        # SuprClaw Cloud Browser

        Use only the SuprClaw cloud browser tools for interactive browsing.

        Workflow:
        - Start with `cloud_browser_open`.
        - Use `cloud_browser_exec` for navigation and interactions.
        - Use `cloud_browser_request_takeover` for MFA, CAPTCHA, payments, or destructive actions.
        - Resume with `cloud_browser_resume` after takeover.
        - Close with `cloud_browser_close` once the task is complete.

        Prefer search or scrape tools for read-only tasks. Use browser automation only when interaction or authenticated state is required.
    """.trimIndent()

    private val marketplaceSkill = """
        # SuprClaw Marketplace Workspace

        Marketplace agents must remain self-contained inside their assigned workspace.

        Rules:
        - Keep install paths stable under `/home/picoclaw/.picoclaw/` or `/home/picoclaw/<marketplace path>`.
        - Preserve `AGENTS.md`, `SOUL.md`, `USER.md`, `IDENTITY.md`, `HEARTBEAT.md`, `memory/`, and `skills/`.
        - Do not assume `TOOLS.md` exists; if it does, treat it as legacy compatibility only.
        - Keep role-specific instructions inside workspace files or local skills, not external MCP wiring.
    """.trimIndent()

    fun buildWorkspaceBootstrapCommand(workspacePath: String, includeLegacyToolsMirror: Boolean): String {
        val commands = mutableListOf<String>()

        commands += "mkdir -p ${singleQuote(workspacePath)}"
        commands += "mkdir -p ${singleQuote("$workspacePath/skills/suprclaw-supabase")}"
        commands += "mkdir -p ${singleQuote("$workspacePath/skills/suprclaw-cloud-browser")}"
        commands += "mkdir -p ${singleQuote("$workspacePath/skills/suprclaw-marketplace")}"
        commands += appendIfMissingCommand("$workspacePath/AGENTS.md", AGENTS_MARKER, agentsGuidance)
        commands += overwriteCommand("$workspacePath/skills/suprclaw-supabase/SKILL.md", supabaseSkill)
        commands += overwriteCommand("$workspacePath/skills/suprclaw-cloud-browser/SKILL.md", cloudBrowserSkill)
        commands += overwriteCommand("$workspacePath/skills/suprclaw-marketplace/SKILL.md", marketplaceSkill)
        if (includeLegacyToolsMirror) {
            commands += appendIfMissingCommand("$workspacePath/TOOLS.md", LEGACY_TOOLS_MARKER, legacyToolsMirror)
        }

        return commands.joinToString(" && ")
    }

    private fun appendIfMissingCommand(filePath: String, marker: String, content: String): String {
        val encoded = encode("\n\n$content\n")
        return "touch ${singleQuote(filePath)} && " +
            "if ! grep -Fq ${singleQuote(marker)} ${singleQuote(filePath)}; then " +
            "printf '%s' ${singleQuote(encoded)} | base64 -d >> ${singleQuote(filePath)}; " +
            "fi"
    }

    private fun overwriteCommand(filePath: String, content: String): String {
        val encoded = encode("$content\n")
        return "printf '%s' ${singleQuote(encoded)} | base64 -d > ${singleQuote(filePath)}"
    }

    private fun encode(content: String): String =
        Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8))
}
