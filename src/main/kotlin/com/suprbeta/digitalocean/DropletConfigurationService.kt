package com.suprbeta.digitalocean

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.server.application.*

interface DropletConfigurationService {
    suspend fun createAgent(userId: String, name: String): String

    suspend fun deleteAgent(userId: String, name: String): String
}

class DropletConfigurationServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : DropletConfigurationService {
    private val logger = application.log
    private val agentNameRegex = Regex("^[a-zA-Z0-9_-]+$")

    override suspend fun createAgent(userId: String, name: String): String {
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

        val command = "openclaw agents add $name --workspace /home/openclaw/.openclaw/workspace-$name"
        logger.info("Creating OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        return sshCommandExecutor.runSshCommand(userDroplet.ipAddress, userDroplet.sshKey, command)
    }

    override suspend fun deleteAgent(userId: String, name: String): String {
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

        val command = "openclaw agents remove $name"
        logger.info("Deleting OpenClaw agent '$name' on droplet ${userDroplet.dropletId}")
        return sshCommandExecutor.runSshCommand(userDroplet.ipAddress, userDroplet.sshKey, command)
    }
}
