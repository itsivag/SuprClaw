package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceAgent
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
    application: Application
) : DropletConfigurationService {
    companion object {
        private const val DEFAULT_AGENT_MODEL = "amazon-bedrock/minimax.minimax-m2.1"
        private val SAFE_MODEL_REGEX = Regex("^[a-zA-Z0-9._:/-]+$")
        private val SAFE_RELATIVE_PATH_REGEX = Regex("^[a-zA-Z0-9._/-]+$")
    }

    private val logger = application.log
    private val agentNameRegex = Regex("^[a-zA-Z0-9_-]+$")

    override suspend fun createAgent(userId: String, name: String, role: String, model: String?): String {
        val userDroplet = validateAndGetDroplet(userId, name)
        val workspacePath = "/home/openclaw/.openclaw/workspace-$name"
        val selectedModel = validateModel(model?.trim()?.ifBlank { DEFAULT_AGENT_MODEL } ?: DEFAULT_AGENT_MODEL)
        val command =
            "openclaw agents add ${singleQuote(name)} --workspace ${singleQuote(workspacePath)} --model ${singleQuote(selectedModel)}"
        logger.info("Creating OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        val output = runAgentCommand(userDroplet, command)

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
        val workspacePath = "/home/openclaw/$safeInstallPath"
        val tmpDir = "/tmp/marketplace-${agent.id}"
        var agentCreated = false

        try {
            // Step 0: Validate required MCP tool env vars are present — always, even if already configured
            if (agent.mcpTools.isNotEmpty()) {
                dropletMcpService.validateMcpTools(agent.mcpTools)
            }

            // Step 0b: Configure MCP tools — always reconfigure if agent requires any, to ensure keys are fresh
            if (agent.mcpTools.isNotEmpty()) {
                val allTools = (userDroplet.configuredMcpTools + agent.mcpTools).distinct()
                logger.info("Configuring MCP tools ${allTools.joinToString()} for agent '${agent.id}' on droplet ${userDroplet.dropletId}")
                dropletMcpService.configureMcpTools(userDroplet, allTools)
                firestoreRepository.updateConfiguredMcpTools(userId, userDroplet.dropletId, allTools)
            }

            // Step 1: Create agent workspace via openclaw CLI
            logger.info("Installing marketplace agent '${agent.id}' on droplet ${userDroplet.dropletId}")
            val output = runAgentCommand(
                userDroplet,
                "openclaw agents add ${singleQuote(agent.id)} --workspace ${singleQuote(workspacePath)} --model ${singleQuote(DEFAULT_AGENT_MODEL)}"
            )
            agentCreated = true

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
                    runAgentCommand(userDroplet, "openclaw agents delete ${singleQuote(agent.id)} --force")
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
        val command = "openclaw agents delete ${singleQuote(name)} --force"
        logger.info("Deleting OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        val output = try {
            runAgentCommand(userDroplet, command, singleAttempt = true)
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("not found", ignoreCase = true)) {
                logger.warn("Agent '$name' not found in openclaw registry, proceeding with cleanup")
                ""
            } else throw e
        }

        val workspacePath = "/home/openclaw/.openclaw/workspace-$name"
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
        val hostCommand = if (userDroplet.isDockerDeployment()) {
            val containerId = userDroplet.containerIdOrNull()
                ?: throw IllegalStateException("Missing container ID for docker deployment")
            "docker exec ${singleQuote(containerId)} su - openclaw -s /bin/sh -lc ${singleQuote(command)}"
        } else {
            command
        }

        return if (singleAttempt) {
            sshCommandExecutor.runSshCommandOnce(userDroplet.ipAddress, hostCommand)
        } else {
            sshCommandExecutor.runSshCommand(userDroplet.ipAddress, hostCommand)
        }
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
