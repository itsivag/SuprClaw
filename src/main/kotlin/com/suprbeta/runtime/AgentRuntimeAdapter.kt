package com.suprbeta.runtime

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.digitalocean.models.UserDropletInternal
import kotlinx.serialization.json.Json

data class AgentRuntimeCreateRequest(
    val agentId: String,
    val workspacePath: String,
    val model: String,
    val defaultAgent: Boolean = false
)

interface AgentRuntimeAdapter {
    val runtime: AgentRuntime

    fun supportsNativeMcpProvisioning(): Boolean

    fun buildCreateAgentCommand(request: AgentRuntimeCreateRequest): String

    fun buildDeleteAgentCommand(agentId: String): String

    fun buildWakeCommand(agentId: String, sessionKey: String, message: String): String?

    fun buildDirectChatCommand(sessionKey: String, message: String): String?

    fun buildPostConfigReloadCommand(): String?
}

class PicoClawRuntimeAdapter : AgentRuntimeAdapter {
    override val runtime: AgentRuntime = AgentRuntime.PICOCLAW

    private val json = Json { prettyPrint = true }

    override fun supportsNativeMcpProvisioning(): Boolean = true

    override fun buildCreateAgentCommand(request: AgentRuntimeCreateRequest): String {
        val defaultAgentField = if (request.defaultAgent) ", default: true" else ""
        val agentConfig = """
            {
              id: ${json.encodeToString(request.agentId)},
              name: ${json.encodeToString(request.agentId)},
              workspace: ${json.encodeToString(request.workspacePath)},
              model: ${json.encodeToString(request.model)}
              $defaultAgentField
            }
        """.trimIndent().replace("\n", " ")
        return buildConfigMutationCommand(
            """
            cfg.agents = cfg.agents || {};
            cfg.agents.defaults = cfg.agents.defaults || {};
            cfg.agents.defaults.workspace = cfg.agents.defaults.workspace || ${json.encodeToString(RuntimePaths.leadWorkspace)};
            cfg.agents.defaults.restrict_to_workspace = true;
            cfg.agents.list = Array.isArray(cfg.agents.list) ? cfg.agents.list.filter((agent) => agent && agent.id !== ${json.encodeToString(request.agentId)}) : [];
            cfg.agents.list.push($agentConfig);
            """.trimIndent()
        )
    }

    override fun buildDeleteAgentCommand(agentId: String): String =
        buildConfigMutationCommand(
            """
            cfg.agents = cfg.agents || {};
            cfg.agents.list = Array.isArray(cfg.agents.list) ? cfg.agents.list.filter((agent) => agent && agent.id !== ${json.encodeToString(agentId)}) : [];
            """.trimIndent()
        )

    override fun buildWakeCommand(agentId: String, sessionKey: String, message: String): String =
        buildDirectCommand(sessionKey = sessionKey, message = message)

    override fun buildDirectChatCommand(sessionKey: String, message: String): String =
        buildDirectCommand(sessionKey = sessionKey, message = message)

    override fun buildPostConfigReloadCommand(): String =
        "supervisorctl restart picoclaw-gateway >/dev/null 2>&1 || true"

    private fun buildDirectCommand(sessionKey: String, message: String): String =
        "PICOCLAW_HOME=${singleQuote(RuntimePaths.runtimeHome)} " +
            "PICOCLAW_CONFIG=${singleQuote(RuntimePaths.picoclawConfig)} " +
            "picoclaw agent --session ${singleQuote(sessionKey)} --message ${singleQuote(message)}"

    private fun buildConfigMutationCommand(body: String): String {
        val script = """
            const fs = require('fs');
            const path = ${json.encodeToString(RuntimePaths.picoclawConfig)};
            const cfg = fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, 'utf8')) : {};
            ${body.trim()}
            fs.writeFileSync(path, JSON.stringify(cfg, null, 2));
        """.trimIndent()
        return "node -e ${singleQuote(script)}"
    }
}

class AgentRuntimeRegistry(
    private val adapters: List<AgentRuntimeAdapter> = listOf(
        PicoClawRuntimeAdapter()
    )
) {
    fun resolve(runtime: AgentRuntime): AgentRuntimeAdapter =
        adapters.first { it.runtime == runtime }

    fun resolve(droplet: UserDropletInternal): AgentRuntimeAdapter =
        resolve(droplet.resolvedAgentRuntime())
}
