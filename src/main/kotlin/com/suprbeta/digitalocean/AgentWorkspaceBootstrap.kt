package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import java.nio.charset.StandardCharsets
import java.util.Base64

object AgentWorkspaceBootstrap {
    private const val CLOUD_BROWSER_MARKER = "SUPRCLAW_CLOUD_BROWSER_TOOLS_V1"

    private val cloudBrowserGuidance = """
        ## SuprClaw Cloud Browser
        <!-- $CLOUD_BROWSER_MARKER -->
        Use the SuprClaw cloud browser tools for interactive browsing and authenticated sessions.
        - Start interactive browser work with `cloud_browser_open`.
        - Use `cloud_browser_exec` for navigation, clicks, form fills, and page inspection.
        - Use `cloud_browser_request_takeover` for CAPTCHA, MFA, login approvals, payments, account changes, and destructive actions.
        - Use `cloud_browser_resume` after the user completes takeover, and `cloud_browser_close` when the browser task is done.
        - Do not use raw `firecrawl_browser_*` tools when `cloud_browser_*` tools are available.
        - Prefer search or scrape tools for read-only extraction; use cloud browser only when the site needs interaction or authenticated state.
    """.trimIndent()

    fun buildCloudBrowserToolsCommand(workspacePath: String): String {
        val filePath = "$workspacePath/TOOLS.md"
        val encodedGuidance = Base64.getEncoder().encodeToString(
            ("\n\n$cloudBrowserGuidance\n").toByteArray(StandardCharsets.UTF_8)
        )

        return buildString {
            append("mkdir -p ${singleQuote(workspacePath)}")
            append(" && touch ${singleQuote(filePath)}")
            append(" && if ! grep -Fq ${singleQuote(CLOUD_BROWSER_MARKER)} ${singleQuote(filePath)}; then ")
            append("printf '%s' ${singleQuote(encodedGuidance)} | base64 -d >> ${singleQuote(filePath)}; ")
            append("fi")
        }
    }
}
