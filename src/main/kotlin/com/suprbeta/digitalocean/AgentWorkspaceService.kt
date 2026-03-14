package com.suprbeta.digitalocean

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.AgentFileContentResponse
import com.suprbeta.digitalocean.models.AgentFileListResponse
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.marketplace.MarketplaceService
import com.suprbeta.runtime.RuntimePaths
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.ktor.server.application.*
import java.util.Base64

interface AgentWorkspaceService {
    suspend fun listWorkspaceFiles(userId: String, dropletId: Long, agentName: String): AgentFileListResponse

    suspend fun getWorkspaceFile(
        userId: String,
        dropletId: Long,
        agentName: String,
        fileName: String
    ): AgentFileContentResponse
}

class AgentWorkspaceServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val agentRepository: SupabaseAgentRepository,
    private val userClientProvider: UserSupabaseClientProvider,
    private val marketplaceService: MarketplaceService,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) : AgentWorkspaceService {
    private val logger = application.log

    companion object {
        private val AGENT_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        private val SAFE_RELATIVE_PATH_REGEX = Regex("^[a-zA-Z0-9._/-]+$")
        private val ALLOWED_FILES = listOf(
            "AGENTS.md",
            "TOOLS.md",
            "SOUL.md",
            "HEARTBEAT.md",
            "BOOTSTRAP.md",
            "IDENTITY.md",
            "USER.md"
        )
        private val PROFILE_VISIBLE_FILES = listOf(
            "SOUL.md",
            "IDENTITY.md",
            "USER.md"
        )
        private const val LEAD_AGENT_NAME = "Lead"
        private const val WORKSPACE_TYPE_LEAD = "lead"
        private const val WORKSPACE_TYPE_MARKETPLACE = "marketplace"
        private const val MISSING_WORKSPACE_MARKER = "__SUPRCLAW_WORKSPACE_MISSING__"
        private const val MISSING_FILE_MARKER = "__SUPRCLAW_WORKSPACE_FILE_MISSING__"
    }

    override suspend fun listWorkspaceFiles(
        userId: String,
        dropletId: Long,
        agentName: String
    ): AgentFileListResponse {
        val context = resolveWorkspaceContext(userId, dropletId, agentName)
        val output = runWorkspaceCommand(context.droplet, buildListCommand(context.workspacePath)).trim()

        if (output == MISSING_WORKSPACE_MARKER) {
            throw NoSuchElementException("Workspace not found for agent '$agentName'")
        }

        val files = output
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it in PROFILE_VISIBLE_FILES }

        return AgentFileListResponse(
            dropletId = dropletId,
            agentName = context.agentName,
            workspaceType = context.workspaceType,
            files = files
        )
    }

    override suspend fun getWorkspaceFile(
        userId: String,
        dropletId: Long,
        agentName: String,
        fileName: String
    ): AgentFileContentResponse {
        validateAllowedFileName(fileName)
        val context = resolveWorkspaceContext(userId, dropletId, agentName)
        val encoded = runWorkspaceCommand(
            context.droplet,
            buildReadCommand("${context.workspacePath}/$fileName")
        ).trim()

        if (encoded == MISSING_FILE_MARKER) {
            throw NoSuchElementException("File '$fileName' not found for agent '$agentName'")
        }

        val content = runCatching {
            String(Base64.getDecoder().decode(encoded))
        }.getOrElse { error ->
            logger.error("Failed to decode workspace file $fileName for agent '$agentName'", error)
            throw IllegalStateException("Failed to decode workspace file")
        }

        return AgentFileContentResponse(
            dropletId = dropletId,
            agentName = context.agentName,
            workspaceType = context.workspaceType,
            fileName = fileName,
            content = content
        )
    }

    private suspend fun resolveWorkspaceContext(
        userId: String,
        dropletId: Long,
        agentName: String
    ): WorkspaceContext {
        validateAgentName(agentName)

        val droplet = firestoreRepository.getUserDropletInternal(userId)
            ?: throw NoSuchElementException("No droplet found for user")

        if (droplet.dropletId != dropletId) {
            throw SecurityException("Droplet does not belong to user")
        }

        if (!droplet.status.equals("active", ignoreCase = true)) {
            throw NoSuchElementException("No active droplet found for user")
        }

        if (agentName == LEAD_AGENT_NAME) {
            return WorkspaceContext(
                droplet = droplet,
                agentName = agentName,
                workspaceType = WORKSPACE_TYPE_LEAD,
                workspacePath = RuntimePaths.leadWorkspace
            )
        }

        val catalog = marketplaceService.getCatalog()
        val marketplaceAgent = catalog.agents.find { it.id == agentName }
            ?: throw NoSuchElementException("Agent '$agentName' not found in marketplace")

        val client = userClientProvider.getClient(
            droplet.resolveSupabaseUrl(),
            droplet.supabaseServiceKey,
            droplet.supabaseSchema
        )
        val installed = agentRepository.getAgents(client).any { it.name == agentName }
        if (!installed) {
            throw NoSuchElementException("Agent '$agentName' is not installed for this user")
        }

        return WorkspaceContext(
            droplet = droplet,
            agentName = agentName,
            workspaceType = WORKSPACE_TYPE_MARKETPLACE,
            workspacePath = "/home/${RuntimePaths.runtimeUser}/${validateRelativePath(marketplaceAgent.installPath)}"
        )
    }

    private fun validateAgentName(agentName: String) {
        if (!AGENT_NAME_REGEX.matches(agentName)) {
            throw IllegalArgumentException("Invalid agent name")
        }
    }

    private fun validateAllowedFileName(fileName: String) {
        if (fileName !in ALLOWED_FILES) {
            throw IllegalArgumentException("Invalid file name")
        }
    }

    private fun validateRelativePath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (
            normalized.isBlank() ||
            normalized.startsWith("/") ||
            normalized.split('/').any { it == ".." } ||
            !SAFE_RELATIVE_PATH_REGEX.matches(normalized)
        ) {
            throw IllegalStateException("Invalid marketplace install path")
        }
        return normalized
    }

    private fun buildListCommand(workspacePath: String): String {
        val fileArgs = PROFILE_VISIBLE_FILES.joinToString(" ") { singleQuote(it) }
        return "if [ -d ${singleQuote(workspacePath)} ]; then " +
            "cd ${singleQuote(workspacePath)} && " +
            "for file in $fileArgs; do [ -f \"\$file\" ] && printf '%s\\n' \"\$file\"; done; " +
            "else printf '$MISSING_WORKSPACE_MARKER'; fi"
    }

    private fun buildReadCommand(filePath: String): String =
        "if [ -f ${singleQuote(filePath)} ]; then " +
            "base64 -w0 ${singleQuote(filePath)}; " +
            "else printf '$MISSING_FILE_MARKER'; fi"

    private fun runWorkspaceCommand(droplet: UserDropletInternal, command: String): String {
        val remoteCommand = if (droplet.isContainerDeployment()) {
            val containerId = droplet.containerIdOrNull()
                ?: throw IllegalStateException("Missing container ID for container deployment")
            "podman exec ${singleQuote(containerId)} sh -lc ${singleQuote(command)}"
        } else {
            command
        }
        return sshCommandExecutor.runSshCommand(droplet.ipAddress, remoteCommand)
    }

    private data class WorkspaceContext(
        val droplet: UserDropletInternal,
        val agentName: String,
        val workspaceType: String,
        val workspacePath: String
    )
}
