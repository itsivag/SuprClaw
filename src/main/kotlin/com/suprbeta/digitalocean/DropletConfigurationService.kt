package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceAgent
import com.suprbeta.supabase.SupabaseAgentRepository
import io.ktor.server.application.*
interface DropletConfigurationService {
    suspend fun createAgent(userId: String, name: String, role: String, model: String? = null): String

    suspend fun deleteAgent(userId: String, name: String): String

    suspend fun installMarketplaceAgent(userId: String, agent: MarketplaceAgent, repo: String): String
}

class DropletConfigurationServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val agentRepository: SupabaseAgentRepository,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : DropletConfigurationService {
    companion object {
        private const val DEFAULT_AGENT_MODEL = "amazon-bedrock/minimax.minimax-m2.1"
    }

    private val logger = application.log
    private val agentNameRegex = Regex("^[a-zA-Z0-9_-]+$")

    override suspend fun createAgent(userId: String, name: String, role: String, model: String?): String {
        val userDroplet = validateAndGetDroplet(userId, name)
        val workspacePath = "/home/openclaw/.openclaw/workspace-$name"
        val selectedModel = model?.trim()?.ifBlank { DEFAULT_AGENT_MODEL } ?: DEFAULT_AGENT_MODEL
        val command = "openclaw agents add $name --workspace $workspacePath --model $selectedModel"
        logger.info("Creating OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        val output = sshCommandExecutor.runSshCommand(userDroplet.ipAddress, userDroplet.sshKey, command)

        val schemaName = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        agentRepository.saveAgent(
            schemaName,
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
        val workspacePath = "/home/openclaw/${agent.installPath}"
        val tmpDir = "/tmp/marketplace-${agent.id}"

        // Create agent workspace via openclaw CLI
        logger.info("Installing marketplace agent '${agent.id}' on droplet ${userDroplet.dropletId}")
        val output = sshCommandExecutor.runSshCommand(
            userDroplet.ipAddress, userDroplet.sshKey,
            "openclaw agents add ${agent.id} --workspace $workspacePath --model $DEFAULT_AGENT_MODEL"
        )

        // Sparse-checkout only the agent's source directory, then overwrite workspace files
        sshCommandExecutor.runSshCommand(
            userDroplet.ipAddress, userDroplet.sshKey,
            "rm -rf $tmpDir" +
            " && git clone --depth=1 --filter=blob:none --sparse $repo $tmpDir" +
            " && git -C $tmpDir sparse-checkout set ${agent.sourcePath}" +
            " && cp -rf $tmpDir/${agent.sourcePath}/. $workspacePath/" +
            " && rm -rf $tmpDir"
        )
        logger.info("Marketplace config applied to $workspacePath for agent '${agent.id}'")

        // Save to Supabase
        val schemaName = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        agentRepository.saveAgent(
            schemaName,
            AgentInsert(
                name = agent.name,
                role = agent.description,
                sessionKey = agent.sessionKey,
                isLead = agent.isLead
            )
        )

        return output
    }

    override suspend fun deleteAgent(userId: String, name: String): String {
        val userDroplet = validateAndGetDroplet(userId, name)
        val command = "openclaw agents delete $name --force"
        logger.info("Deleting OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        val output = sshCommandExecutor.runSshCommand(userDroplet.ipAddress, userDroplet.sshKey, command)

        val workspacePath = "/home/openclaw/.openclaw/workspace-$name"
        sshCommandExecutor.runSshCommand(userDroplet.ipAddress, userDroplet.sshKey, "rm -rf $workspacePath")
        logger.info("Deleted workspace '$workspacePath' on droplet ${userDroplet.dropletId}")

        val schemaName = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        agentRepository.deleteAgent(schemaName, name)
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

        if (userDroplet.sshKey.isBlank()) {
            throw IllegalStateException("SSH key is not available for this droplet")
        }

        return userDroplet
    }
}
