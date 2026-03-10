package com.suprbeta.connector

import com.suprbeta.digitalocean.DropletMcpService
import com.suprbeta.digitalocean.McpToolRuntimeConfig
import com.suprbeta.firebase.FirestoreRepository
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.net.URI
import java.time.Instant

interface ConnectorService {
    fun getZapierEmbedConfig(): ZapierEmbedConfigResponse
    suspend fun listConnectors(userId: String): List<ConnectorView>
    suspend fun connectZapier(userId: String, request: ConnectZapierRequest): ConnectorView
    suspend fun updateConnectorPolicy(userId: String, provider: String, allowedAgents: List<String>): ConnectorView
    suspend fun disconnectConnector(userId: String, provider: String)
}

class ConnectorServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val dropletMcpService: DropletMcpService,
    application: Application
) : ConnectorService {
    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    companion object {
        private const val PROVIDER_ZAPIER = "zapier"
        private val CONNECTOR_TOOLS = setOf(PROVIDER_ZAPIER)
    }

    override fun getZapierEmbedConfig(): ZapierEmbedConfigResponse {
        val embedId = env("ZAPIER_MCP_EMBED_ID")
        if (embedId.isBlank()) {
            throw IllegalStateException("ZAPIER_MCP_EMBED_ID is not configured")
        }
        return ZapierEmbedConfigResponse(embedId = embedId)
    }

    override suspend fun listConnectors(userId: String): List<ConnectorView> {
        return firestoreRepository.listUserConnectorsInternal(userId)
            .map { it.toView(hasCredential = hasZapierCredential()) }
    }

    override suspend fun connectZapier(userId: String, request: ConnectZapierRequest): ConnectorView {
        ensureZapierCredentialConfigured()
        val now = Instant.now().toString()
        val normalizedUrl = normalizeZapierUrl(request.mcpServerUrl)

        val existing = firestoreRepository.getUserConnectorInternal(userId, PROVIDER_ZAPIER)
        val connector = ConnectorInternal(
            provider = PROVIDER_ZAPIER,
            status = "connected",
            mcpServerUrl = normalizedUrl,
            enabledApps = normalizeEnabledApps(request.enabledApps),
            allowedAgents = normalizeAllowedAgents(request.allowedAgents),
            createdAt = existing?.createdAt?.ifBlank { now } ?: now,
            updatedAt = now,
            lastError = null
        )

        firestoreRepository.saveUserConnector(userId, connector)
        reconcileMcpForUser(userId)
        logger.info("Connector '$PROVIDER_ZAPIER' connected for userId=$userId")
        return connector.toView(hasCredential = true)
    }

    override suspend fun updateConnectorPolicy(userId: String, provider: String, allowedAgents: List<String>): ConnectorView {
        val normalizedProvider = provider.lowercase()
        val existing = firestoreRepository.getUserConnectorInternal(userId, normalizedProvider)
            ?: throw NoSuchElementException("Connector '$normalizedProvider' is not connected")
        val updated = existing.copy(
            allowedAgents = normalizeAllowedAgents(allowedAgents),
            updatedAt = Instant.now().toString()
        )
        firestoreRepository.saveUserConnector(userId, updated)
        return updated.toView(hasCredential = hasZapierCredential())
    }

    override suspend fun disconnectConnector(userId: String, provider: String) {
        val normalizedProvider = provider.lowercase()
        firestoreRepository.deleteUserConnector(userId, normalizedProvider)
        reconcileMcpForUser(userId)
        logger.info("Connector '$normalizedProvider' disconnected for userId=$userId")
    }

    private suspend fun reconcileMcpForUser(userId: String) {
        ensureZapierCredentialConfigured()
        val droplet = firestoreRepository.getUserDropletInternal(userId) ?: return
        if (!droplet.status.equals("active", ignoreCase = true)) return

        val activeConnectors = firestoreRepository.listUserConnectorsInternal(userId)
            .filter { it.status.equals("connected", ignoreCase = true) }
        val connectorTools = activeConnectors
            .mapNotNull { connectorToolName(it.provider) }

        val stableTools = droplet.configuredMcpTools.filterNot { it in CONNECTOR_TOOLS }
        val allTools = (stableTools + connectorTools).distinct()

        val runtimeConfigByTool = activeConnectors.mapNotNull { connector ->
            val toolName = connectorToolName(connector.provider) ?: return@mapNotNull null
            toolName to McpToolRuntimeConfig(
                upstreamOverride = connector.mcpServerUrl.ifBlank { null }
            )
        }.toMap()

        dropletMcpService.configureMcpTools(droplet, allTools, runtimeConfigByTool)
        firestoreRepository.updateConfiguredMcpTools(userId, droplet.dropletId, allTools)
    }

    private fun connectorToolName(provider: String): String? = when (provider.lowercase()) {
        PROVIDER_ZAPIER -> PROVIDER_ZAPIER
        else -> null
    }

    private fun normalizeEnabledApps(enabledApps: List<String>): List<String> {
        val allowed = setOf("gmail", "calendar", "drive", "docs")
        val normalized = enabledApps.map { it.trim().lowercase() }
            .filter { it in allowed }
            .distinct()
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("enabledApps must include at least one supported app")
        }
        return normalized
    }

    private fun normalizeAllowedAgents(allowedAgents: List<String>): List<String> {
        val regex = Regex("^[a-zA-Z0-9_-]+$")
        return allowedAgents.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .onEach {
                if (!regex.matches(it)) {
                    throw IllegalArgumentException("Invalid agent id '$it'")
                }
            }
    }

    private fun normalizeZapierUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid mcpServerUrl")
        }

        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Invalid mcpServerUrl host")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("mcpServerUrl must use https")
        }
        if (!(host == "mcp.zapier.com" || host.endsWith(".zapier.com"))) {
            throw IllegalArgumentException("mcpServerUrl host must be a Zapier MCP endpoint")
        }
        return uri.normalize().toString()
    }

    private fun hasZapierCredential(): Boolean = env("ZAPIER_MCP_EMBED_SECRET").isNotBlank()

    private fun ensureZapierCredentialConfigured() {
        if (!hasZapierCredential()) {
            throw IllegalStateException("ZAPIER_MCP_EMBED_SECRET is not configured")
        }
    }

    private fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""
}

private fun ConnectorInternal.toView(hasCredential: Boolean): ConnectorView {
    val host = runCatching { URI(mcpServerUrl).host ?: "" }.getOrDefault("")
    return ConnectorView(
        provider = provider,
        status = status,
        enabledApps = enabledApps,
        allowedAgents = allowedAgents,
        mcpHost = host,
        hasCredential = hasCredential,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastError = lastError
    )
}
