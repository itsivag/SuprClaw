package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.AgentInsert
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.supabase.SupabaseAgentRepository
import io.ktor.server.application.*

interface DropletConfigurationService {
    suspend fun createAgent(userId: String, name: String, role: String, model: String? = null): String

    suspend fun deleteAgent(userId: String, name: String): String
}

class DropletConfigurationServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val agentRepository: SupabaseAgentRepository,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : DropletConfigurationService {
    companion object {
        private const val DEFAULT_AGENT_MODEL = "google/gemini-2.5-flash"
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

        agentRepository.saveAgent(
            AgentInsert(
                name = name,
                role = role,
                sessionKey = "agent:$name:main",
                isLead = false
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

        agentRepository.deleteAgent(name)
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
