package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceAgent
import com.suprbeta.runtime.AgentRuntimeCreateRequest
import com.suprbeta.runtime.AgentRuntimeRegistry
import com.suprbeta.runtime.RuntimeCommandExecutor
import com.suprbeta.runtime.RuntimePaths
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.ktor.server.application.*

interface DropletConfigurationService {
    suspend fun createAgent(userId: String, name: String, role: String, model: String? = null): String

    suspend fun deleteAgent(userId: String, name: String): String

    suspend fun installMarketplaceAgent(userId: String, agent: MarketplaceAgent, repo: String): String
}

class DropletConfigurationServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val agentRepository: SupabaseAgentRepository,
    private val userClientProvider: UserSupabaseClientProvider,
    private val sshCommandExecutor: SshCommandExecutor,
    private val dropletMcpService: DropletMcpService,
    private val runtimeRegistry: AgentRuntimeRegistry,
    application: Application
) : DropletConfigurationService {
    companion object {
        private const val DEFAULT_AGENT_MODEL = "suprclaw-default"
        private val SAFE_MODEL_REGEX = Regex("^[a-zA-Z0-9._:/-]+$")
        private val SAFE_RELATIVE_PATH_REGEX = Regex("^[a-zA-Z0-9._/-]+$")
    }

    private val logger = application.log
    private val agentNameRegex = Regex("^[a-zA-Z0-9_-]+$")
    private val commandExecutor = RuntimeCommandExecutor(sshCommandExecutor)

    override suspend fun createAgent(userId: String, name: String, role: String, model: String?): String {
        val userDroplet = validateAndGetDroplet(userId, name)
        val workspacePath = "${RuntimePaths.runtimeHome}/workspace-$name"
        val selectedModel = validateModel(model?.trim()?.ifBlank { DEFAULT_AGENT_MODEL } ?: DEFAULT_AGENT_MODEL)
        val runtimeAdapter = runtimeRegistry.resolve(userDroplet)
        val command = runtimeAdapter.buildCreateAgentCommand(
            AgentRuntimeCreateRequest(
                agentId = name,
                workspacePath = workspacePath,
                model = selectedModel
            )
        )
        logger.info("Creating ${runtimeAdapter.runtime.wireValue} agent '$name' on droplet ${userDroplet.dropletId}")
        val output = runAgentCommand(userDroplet, command)
        runtimeAdapter.buildPostConfigReloadCommand()?.let { runAgentCommand(userDroplet, it) }
        runAgentCommand(
            userDroplet,
            AgentWorkspaceBootstrap.buildWorkspaceBootstrapCommand(
                workspacePath = workspacePath,
                includeLegacyToolsMirror = false
            )
        )

        val client = userClientProvider.getClient(userDroplet.resolveSupabaseUrl(), userDroplet.supabaseServiceKey, userDroplet.supabaseSchema)
        agentRepository.saveAgent(
            client,
            AgentInsert(
                name = name,
                role = role,
                sessionKey = "agent:$name:main",
                isLead = false
            )
        )

        return output
    }

    override suspend fun installMarketplaceAgent(userId: String, agent: MarketplaceAgent, repo: String): String {
        val userDroplet = validateAndGetDroplet(userId, agent.id)
        val safeInstallPath = validateRelativePath(agent.installPath, "install path")
        val safeSourcePath = validateRelativePath(agent.sourcePath, "source path")
        val workspacePath = "/home/${RuntimePaths.runtimeUser}/$safeInstallPath"
        val tmpDir = "/tmp/marketplace-${agent.id}"
        var agentCreated = false
        val runtimeAdapter = runtimeRegistry.resolve(userDroplet)

        try {
            if (agent.mcpTools.isNotEmpty() && runtimeAdapter.supportsNativeMcpProvisioning()) {
                dropletMcpService.validateMcpTools(agent.mcpTools)
            }

            if (agent.mcpTools.isNotEmpty() && runtimeAdapter.supportsNativeMcpProvisioning()) {
                val allTools = (userDroplet.configuredMcpTools + agent.mcpTools).distinct()
                logger.info("Configuring MCP tools ${allTools.joinToString()} for agent '${agent.id}' on droplet ${userDroplet.dropletId}")
                dropletMcpService.configureMcpTools(userDroplet, allTools)
                firestoreRepository.updateConfiguredMcpTools(userId, userDroplet.dropletId, allTools)
            } else if (agent.mcpTools.isNotEmpty()) {
                logger.info(
                    "Skipping legacy MCP provisioning for agent '${agent.id}' on ${runtimeAdapter.runtime.wireValue}; " +
                        "workspace skills are now the primary integration path"
                )
            }

            logger.info("Installing marketplace agent '${agent.id}' on droplet ${userDroplet.dropletId}")
            val output = runAgentCommand(
                userDroplet,
                runtimeAdapter.buildCreateAgentCommand(
                    AgentRuntimeCreateRequest(
                        agentId = agent.id,
                        workspacePath = workspacePath,
                        model = DEFAULT_AGENT_MODEL
                    )
                )
            )
            agentCreated = true
            runtimeAdapter.buildPostConfigReloadCommand()?.let { runAgentCommand(userDroplet, it) }

            // Step 2: Sparse-checkout only the agent's source directory, then overwrite workspace files
            runAgentCommand(
                userDroplet,
                "rm -rf ${singleQuote(tmpDir)}" +
                    " && git clone --depth=1 --filter=blob:none --sparse ${singleQuote(repo)} ${singleQuote(tmpDir)}" +
                    " && git -C ${singleQuote(tmpDir)} sparse-checkout set -- ${singleQuote(safeSourcePath)}" +
                    " && git -C ${singleQuote(tmpDir)} checkout" +
                    " && mkdir -p ${singleQuote(workspacePath)}" +
                    " && cp -rf ${singleQuote("$tmpDir/$safeSourcePath/.")} ${singleQuote("$workspacePath/")}" +
                    " && rm -rf ${singleQuote(tmpDir)}"
            )
            runAgentCommand(
                userDroplet,
                AgentWorkspaceBootstrap.buildWorkspaceBootstrapCommand(
                    workspacePath = workspacePath,
                    includeLegacyToolsMirror = false
                )
            )
            logger.info("Marketplace config applied to $workspacePath for agent '${agent.id}'")

            // Step 3: Save to user's Supabase project
            val client = userClientProvider.getClient(userDroplet.resolveSupabaseUrl(), userDroplet.supabaseServiceKey, userDroplet.supabaseSchema)
            agentRepository.saveAgent(
                client,
                AgentInsert(
                    name = agent.id,
                    role = agent.description,
                    sessionKey = agent.sessionKey,
                    isLead = agent.isLead
                )
            )

            return output
        } catch (e: Exception) {
            if (agentCreated) {
                logger.warn("Installation failed for agent '${agent.id}', rolling back...")
                runCatching {
                    runAgentCommand(userDroplet, runtimeAdapter.buildDeleteAgentCommand(agent.id))
                    runtimeAdapter.buildPostConfigReloadCommand()?.let { runAgentCommand(userDroplet, it) }
                    logger.info("Rollback successful: agent '${agent.id}' deleted")
                }.onFailure { rollbackError ->
                    logger.error("Rollback failed for agent '${agent.id}': ${rollbackError.message}")
                }
            }
            throw e
        }
    }

    override suspend fun deleteAgent(userId: String, name: String): String {
        val userDroplet = validateAndGetDroplet(userId, name)
        val runtimeAdapter = runtimeRegistry.resolve(userDroplet)
        val command = runtimeAdapter.buildDeleteAgentCommand(name)
        logger.info("Deleting ${runtimeAdapter.runtime.wireValue} agent '$name' on droplet ${userDroplet.dropletId}")
        val output = try {
            runAgentCommand(userDroplet, command, singleAttempt = true)
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("not found", ignoreCase = true)) {
                logger.warn("Agent '$name' not found in runtime registry, proceeding with cleanup")
                ""
            } else throw e
        }
        runtimeAdapter.buildPostConfigReloadCommand()?.let { runAgentCommand(userDroplet, it) }

        val workspacePath = "${RuntimePaths.runtimeHome}/workspace-$name"
        runAgentCommand(userDroplet, "rm -rf ${singleQuote(workspacePath)}")
        logger.info("Deleted workspace '$workspacePath' on droplet ${userDroplet.dropletId}")

        val client = userClientProvider.getClient(userDroplet.resolveSupabaseUrl(), userDroplet.supabaseServiceKey, userDroplet.supabaseSchema)
        agentRepository.deleteAgent(client, name)
        return output
    }

    private suspend fun validateAndGetDroplet(userId: String, name: String): UserDropletInternal {
        if (!agentNameRegex.matches(name)) {
            throw IllegalArgumentException("Invalid agent name. Use only letters, numbers, _ and -")
        }

        val userDroplet = firestoreRepository.getUserDropletInternal(userId)
            ?: throw IllegalStateException("No droplet found for user")

        if (!userDroplet.status.equals("active", ignoreCase = true)) {
            throw IllegalStateException("Droplet is not active")
        }

        return userDroplet
    }

    private fun runAgentCommand(userDroplet: UserDropletInternal, command: String, singleAttempt: Boolean = false): String {
        return commandExecutor.run(userDroplet, command, singleAttempt)
    }

    private fun validateModel(model: String): String {
        if (!SAFE_MODEL_REGEX.matches(model)) {
            throw IllegalArgumentException("Invalid model. Use only letters, numbers, ., _, :, / and -")
        }
        return model
    }

    private fun validateRelativePath(path: String, label: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (
            normalized.isBlank() ||
            normalized.startsWith("/") ||
            normalized.split('/').any { it == ".." } ||
            !SAFE_RELATIVE_PATH_REGEX.matches(normalized)
        ) {
            throw IllegalArgumentException("Invalid $label")
        }
        return normalized
    }
}
