package com.suprbeta.connector

import com.suprbeta.digitalocean.DropletMcpService
import com.suprbeta.digitalocean.McpToolRuntimeConfig
import com.suprbeta.firebase.FirestoreRepository
import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface ConnectorService {
    suspend fun listConnectors(userId: String): List<ConnectorProviderView>
    suspend fun listConnectorAccounts(userId: String, provider: String): List<ConnectorAccountView>
    suspend fun listConnectorTools(userId: String, provider: String, accountId: String): ConnectorToolsResponse
    suspend fun invokeConnectorAction(
        userId: String,
        provider: String,
        accountId: String,
        actionName: String,
        request: ConnectorActionInvokeRequest
    ): ConnectorActionInvokeResponse

    suspend fun updateConnectorPolicy(
        userId: String,
        provider: String,
        accountId: String,
        allowedAgents: List<String>,
        isDefaultForProvider: Boolean?,
        defaultForAgents: List<String>?
    ): ConnectorAccountView

    suspend fun disconnectConnector(userId: String, provider: String, accountId: String)
    suspend fun startNangoConnectorSession(
        userId: String,
        userEmail: String?,
        provider: String,
        request: ConnectorSessionStartRequest
    ): ConnectorSessionStartResponse

    suspend fun getConnectorSessionStatus(userId: String, sessionId: String): ConnectorSessionStatusResponse
    suspend fun handleNangoWebhook(rawBody: String, signatureHeader: String?)
    fun forwardNangoOAuthCallback(provider: String, queryParameters: Parameters): String

    suspend fun startConnectorSession(userId: String): ConnectorSessionStartResponse
    suspend fun getSessionConnectPage(sessionId: String, state: String): ConnectorConnectPage
    suspend fun finalizeConnectorCallback(state: String, mcpServerUrl: String?): ConnectorSessionCallbackResponse
    fun callbackRedirectUrl(callbackResponse: ConnectorSessionCallbackResponse): String?
}

class ConnectorServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val dropletMcpService: DropletMcpService,
    private val nangoService: NangoService,
    application: Application
) : ConnectorService {
    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    private val json = Json { ignoreUnknownKeys = true }
    private val stateJson = Json { ignoreUnknownKeys = true }

    companion object {
        private const val PROVIDER_ZAPIER = "zapier"
        private const val PROVIDER_INTERNAL_NANGO = "nango"
        private val CONNECTOR_TOOLS = setOf(PROVIDER_ZAPIER)
        private const val DEFAULT_CONNECTOR_BASE_URL = "https://api.suprclaw.com"
        private const val DEFAULT_SESSION_TTL_SECONDS = 900L
        private val DEFAULT_ENABLED_APPS = listOf("gmail", "calendar", "drive", "docs")
        private const val NANGO_SESSION_TAG = "suprclaw_session_id"
        private const val NANGO_AGENT_TAG = "suprclaw_agent_id"
    }

    private val connectorPublicBaseUrl: String by lazy {
        val configured = env("CONNECTOR_PUBLIC_BASE_URL")
            .ifBlank { env("WEBHOOK_BASE_URL") }
            .ifBlank { DEFAULT_CONNECTOR_BASE_URL }
        configured.trimEnd('/')
    }

    private val sessionTtlSeconds: Long by lazy {
        env("CONNECTOR_SESSION_TTL_SECONDS").toLongOrNull()?.coerceAtLeast(60) ?: DEFAULT_SESSION_TTL_SECONDS
    }

    private val sessionSigningSecret: String by lazy {
        env("CONNECTOR_SESSION_SIGNING_SECRET").ifBlank { env("ZAPIER_MCP_EMBED_SECRET") }
    }

    private val callbackSuccessUrl: String by lazy { env("CONNECTOR_CALLBACK_SUCCESS_URL") }
    private val callbackFailureUrl: String by lazy { env("CONNECTOR_CALLBACK_FAILURE_URL") }
    private val zapierEmbedId: String by lazy { env("ZAPIER_MCP_EMBED_ID") }

    override suspend fun listConnectors(userId: String): List<ConnectorProviderView> {
        val connectors = firestoreRepository.listUserConnectorsInternal(userId)
        return connectors
            .groupBy { it.provider.lowercase() }
            .map { (provider, accounts) ->
                ConnectorProviderView(
                    provider = provider,
                    providerDisplayName = providerDisplayName(provider),
                    accounts = accounts
                        .sortedWith(compareByDescending<ConnectorInternal> { it.isDefaultForProvider }.thenBy { it.createdAt })
                        .map { it.toAccountView() }
                )
            }
            .sortedBy { it.providerDisplayName.lowercase() }
    }

    override suspend fun listConnectorAccounts(userId: String, provider: String): List<ConnectorAccountView> {
        return firestoreRepository.listUserConnectorsByProviderInternal(userId, provider.lowercase())
            .sortedWith(compareByDescending<ConnectorInternal> { it.isDefaultForProvider }.thenBy { it.createdAt })
            .map { it.toAccountView() }
    }

    override suspend fun listConnectorTools(userId: String, provider: String, accountId: String): ConnectorToolsResponse {
        val account = resolveConnectedAccount(userId, provider, accountId)
        val tools = nangoService.listActionTools(account.providerConfigKey)
            .map { tool ->
                ConnectorToolDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            }

        return ConnectorToolsResponse(
            provider = account.provider,
            accountId = account.accountId.ifBlank { accountId },
            providerConfigKey = account.providerConfigKey,
            tools = tools
        )
    }

    override suspend fun invokeConnectorAction(
        userId: String,
        provider: String,
        accountId: String,
        actionName: String,
        request: ConnectorActionInvokeRequest
    ): ConnectorActionInvokeResponse {
        val account = resolveConnectedAccount(userId, provider, accountId)
        if (account.connectionId.isBlank() || account.providerConfigKey.isBlank()) {
            throw IllegalStateException("Connector account '$accountId' is missing Nango connection details")
        }

        val result = nangoService.triggerAction(
            providerConfigKey = account.providerConfigKey,
            connectionId = account.connectionId,
            actionName = actionName,
            input = request.input,
            async = request.async,
            maxRetries = request.maxRetries
        )

        return ConnectorActionInvokeResponse(
            actionName = actionName,
            async = result.async,
            output = result.output,
            actionId = result.actionId,
            statusUrl = result.statusUrl
        )
    }

    override suspend fun updateConnectorPolicy(
        userId: String,
        provider: String,
        accountId: String,
        allowedAgents: List<String>,
        isDefaultForProvider: Boolean?,
        defaultForAgents: List<String>?
    ): ConnectorAccountView {
        val normalizedProvider = provider.lowercase()
        val target = firestoreRepository.getUserConnectorInternalById(userId, accountId)
            ?: throw NoSuchElementException("Connector account '$accountId' not found")
        if (!target.provider.equals(normalizedProvider, ignoreCase = true)) {
            throw IllegalArgumentException("Connector account '$accountId' does not belong to provider '$normalizedProvider'")
        }

        val now = Instant.now().toString()
        val normalizedAllowedAgents = normalizeAllowedAgents(allowedAgents)
        val normalizedDefaultForAgents = defaultForAgents?.let(::normalizeAllowedAgents)
        val siblings = firestoreRepository.listUserConnectorsByProviderInternal(userId, normalizedProvider)
            .filter { it.accountId != target.accountId }
        val agentsClaimedAsDefault = normalizedDefaultForAgents.orEmpty().toSet()
        siblings
            .mapNotNull { sibling ->
                var updatedSibling = sibling
                var changed = false

                if (isDefaultForProvider == true && sibling.isDefaultForProvider) {
                    updatedSibling = updatedSibling.copy(isDefaultForProvider = false, updatedAt = now)
                    changed = true
                }

                if (agentsClaimedAsDefault.isNotEmpty() && sibling.defaultForAgents.any { it in agentsClaimedAsDefault }) {
                    updatedSibling = updatedSibling.copy(
                        defaultForAgents = updatedSibling.defaultForAgents.filterNot { it in agentsClaimedAsDefault },
                        updatedAt = now
                    )
                    changed = true
                }

                updatedSibling.takeIf { changed }
            }
            .forEach { sibling -> firestoreRepository.saveUserConnector(userId, sibling) }

        val updated = target.copy(
            allowedAgents = normalizedAllowedAgents,
            isDefaultForProvider = isDefaultForProvider ?: target.isDefaultForProvider,
            defaultForAgents = normalizedDefaultForAgents ?: target.defaultForAgents,
            updatedAt = now
        )
        firestoreRepository.saveUserConnector(userId, updated)
        return updated.toAccountView()
    }

    override suspend fun disconnectConnector(userId: String, provider: String, accountId: String) {
        val normalizedProvider = provider.lowercase()
        val connector = firestoreRepository.getUserConnectorInternalById(userId, accountId)
            ?: throw NoSuchElementException("Connector account '$accountId' not found")
        if (!connector.provider.equals(normalizedProvider, ignoreCase = true)) {
            throw IllegalArgumentException("Connector account '$accountId' does not belong to provider '$normalizedProvider'")
        }

        if (connector.connectionId.isNotBlank() && connector.providerConfigKey.isNotBlank()) {
            runCatching {
                nangoService.deleteConnection(
                    providerConfigKey = connector.providerConfigKey,
                    connectionId = connector.connectionId
                )
            }.onFailure {
                logger.warn("Failed to delete Nango connection ${connector.connectionId} for userId=$userId", it)
            }
        }

        if (connector.accountId.isNotBlank()) {
            firestoreRepository.deleteUserConnectorById(userId, connector.accountId)
        } else {
            firestoreRepository.deleteUserConnector(userId, normalizedProvider)
        }

        if (normalizedProvider == PROVIDER_ZAPIER) {
            reconcileMcpForUser(userId)
        }
        logger.info("Connector '$normalizedProvider' accountId=$accountId disconnected for userId=$userId")
    }

    override suspend fun startNangoConnectorSession(
        userId: String,
        userEmail: String?,
        provider: String,
        request: ConnectorSessionStartRequest
    ): ConnectorSessionStartResponse {
        val providerDef = nangoService.requireProvider(provider)
        val now = Instant.now()
        val sessionId = "session_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val tags = buildMap {
            put("end_user_id", userId)
            userEmail?.takeIf { it.isNotBlank() }?.let { put("end_user_email", it) }
            put(NANGO_SESSION_TAG, sessionId)
            request.agentId?.takeIf { it.isNotBlank() }?.let { put(NANGO_AGENT_TAG, it) }
        }

        val reconnectTarget = request.accountId?.takeIf { it.isNotBlank() }?.let { accountId ->
            firestoreRepository.getUserConnectorInternalById(userId, accountId)
                ?: throw NoSuchElementException("Connector account '$accountId' not found")
        }

        val nangoSession = if (reconnectTarget != null) {
            if (!reconnectTarget.provider.equals(providerDef.provider, ignoreCase = true)) {
                throw IllegalArgumentException("Connector account '$request.accountId' does not belong to provider '${providerDef.provider}'")
            }
            if (reconnectTarget.connectionId.isBlank()) {
                throw IllegalStateException("Connector account '${reconnectTarget.accountId}' is missing a connection ID")
            }
            nangoService.createReconnectSession(
                provider = providerDef.copy(
                    providerConfigKey = reconnectTarget.providerConfigKey.ifBlank { providerDef.providerConfigKey }
                ),
                connectionId = reconnectTarget.connectionId,
                tags = tags
            )
        } else {
            nangoService.createConnectSession(providerDef, tags)
        }

        val session = ConnectorSessionInternal(
            id = sessionId,
            userId = userId,
            status = ConnectorSessionStatus.PENDING,
            providerInternal = PROVIDER_INTERNAL_NANGO,
            connectUrl = nangoSession.connectLink.orEmpty(),
            callbackState = "",
            createdAt = now.toString(),
            updatedAt = now.toString(),
            expiresAt = nangoSession.expiresAt,
            error = null,
            connectorId = reconnectTarget?.accountId,
            enabledApps = emptyList(),
            allowedAgents = emptyList(),
            provider = providerDef.provider,
            providerConfigKey = reconnectTarget?.providerConfigKey?.ifBlank { providerDef.providerConfigKey }
                ?: providerDef.providerConfigKey,
            nangoSessionToken = nangoSession.token,
            requestedScopes = request.scopePreset?.let(::listOf) ?: emptyList(),
            redirectUri = request.returnPath.orEmpty(),
            connectionId = reconnectTarget?.connectionId,
            accountId = reconnectTarget?.accountId,
            agentId = request.agentId,
            returnPath = request.returnPath
        )
        firestoreRepository.saveConnectorSession(session)

        return ConnectorSessionStartResponse(
            sessionId = session.id,
            sessionToken = nangoSession.token,
            providerConfigKey = session.providerConfigKey,
            expiresAt = session.expiresAt,
            connectUrl = nangoSession.connectLink
        )
    }

    override suspend fun getConnectorSessionStatus(userId: String, sessionId: String): ConnectorSessionStatusResponse {
        val session = firestoreRepository.getConnectorSession(sessionId)
            ?: throw NoSuchElementException("Connector session '$sessionId' not found")
        if (session.userId != userId) throw NoSuchElementException("Connector session '$sessionId' not found")

        val normalized = normalizeSessionStatus(session)
        if (normalized != session) {
            firestoreRepository.saveConnectorSession(normalized)
        }

        val account = normalized.accountId?.takeIf { it.isNotBlank() }?.let {
            firestoreRepository.getUserConnectorInternalById(userId, it)?.toAccountView()
        }

        return ConnectorSessionStatusResponse(
            sessionId = normalized.id,
            provider = normalized.provider.ifBlank { normalized.providerInternal },
            status = normalized.status,
            error = normalized.error,
            account = account
        )
    }

    override suspend fun handleNangoWebhook(rawBody: String, signatureHeader: String?) {
        if (!nangoService.verifyWebhookSignature(rawBody, signatureHeader)) {
            throw IllegalArgumentException("Invalid Nango webhook signature")
        }

        val payload = json.parseToJsonElement(rawBody).jsonObject
        val type = payload.string("type")
        val operation = payload.string("operation")
        if (type != "auth" || operation.isNullOrBlank()) {
            return
        }

        val tags = payload.objectValue("tags").stringMap()
        val sessionId = tags[NANGO_SESSION_TAG]
        val session = sessionId?.let { firestoreRepository.getConnectorSession(it) }
        val provider = payload.string("provider")
            ?: session?.provider
            ?: return
        val userId = tags["end_user_id"]
            ?: session?.userId
            ?: payload.objectValue("endUser").string("endUserId")
            ?: return
        val connectionId = payload.string("connectionId").orEmpty()
        val providerConfigKey = payload.string("providerConfigKey")
            ?: session?.providerConfigKey
            ?: nangoService.requireProvider(provider).providerConfigKey
        val success = payload.boolean("success") ?: false
        val now = Instant.now().toString()

        when (operation) {
            "creation", "override" -> {
                if (!success) {
                    markSessionFailure(session, payload.errorDescription())
                    return
                }

                val existing = firestoreRepository.findUserConnectorByConnectionInternal(userId, provider, connectionId)
                    ?: session?.accountId?.let { firestoreRepository.getUserConnectorInternalById(userId, it) }

                val connection = nangoService.getConnection(providerConfigKey, connectionId)
                val connector = ConnectorInternal(
                    provider = provider.lowercase(),
                    accountId = existing?.accountId?.ifBlank { connection.connectionId } ?: connection.connectionId,
                    connectionId = connection.connectionId,
                    providerConfigKey = connection.providerConfigKey,
                    externalAccountId = connection.metadata["external_account_id"]
                        ?: connection.tags["external_account_id"]
                        ?: existing?.externalAccountId,
                    displayName = connection.tags["end_user_display_name"]
                        ?: connection.metadata["display_name"]
                        ?: existing?.displayName,
                    email = connection.tags["end_user_email"] ?: existing?.email,
                    avatarUrl = connection.metadata["avatar_url"] ?: existing?.avatarUrl,
                    status = ConnectorAccountStatus.CONNECTED,
                    grantedScopes = extractScopes(connection, existing),
                    allowedAgents = existing?.allowedAgents ?: emptyList(),
                    isDefaultForProvider = existing?.isDefaultForProvider
                        ?: firestoreRepository.listUserConnectorsByProviderInternal(userId, provider).isEmpty(),
                    defaultForAgents = existing?.defaultForAgents ?: emptyList(),
                    lastValidatedAt = now,
                    createdAt = existing?.createdAt?.ifBlank { now } ?: now,
                    updatedAt = now,
                    lastError = null,
                    mcpServerUrl = existing?.mcpServerUrl.orEmpty(),
                    enabledApps = existing?.enabledApps ?: emptyList()
                )
                firestoreRepository.saveUserConnector(userId, connector)
                if (session != null) {
                    firestoreRepository.saveConnectorSession(
                        session.copy(
                            status = ConnectorSessionStatus.COMPLETED,
                            updatedAt = now,
                            error = null,
                            connectorId = connector.accountId,
                            accountId = connector.accountId,
                            connectionId = connector.connectionId,
                            provider = connector.provider,
                            providerConfigKey = connector.providerConfigKey
                        )
                    )
                }
            }

            "refresh" -> {
                val existing = firestoreRepository.findUserConnectorByConnectionInternal(userId, provider, connectionId)
                if (existing != null) {
                    firestoreRepository.saveUserConnector(
                        userId,
                        existing.copy(
                            status = ConnectorAccountStatus.RECONNECT_REQUIRED,
                            updatedAt = now,
                            lastValidatedAt = now,
                            lastError = payload.errorDescription() ?: "Nango token refresh failed"
                        )
                    )
                }
                markSessionFailure(session, payload.errorDescription() ?: "Nango token refresh failed")
            }
        }
    }

    override fun forwardNangoOAuthCallback(provider: String, queryParameters: Parameters): String {
        return nangoService.buildCallbackForwardUrl(provider, queryParameters)
    }

    override suspend fun startConnectorSession(userId: String): ConnectorSessionStartResponse {
        ensureZapierCredentialConfigured()
        ensureSessionSigningConfigured()
        ensureZapierEmbedConfigured()

        val now = Instant.now()
        val expiresAt = now.plusSeconds(sessionTtlSeconds)
        val sessionId = "session_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val signedState = signState(
            SignedStatePayload(
                sessionId = sessionId,
                userId = userId,
                issuedAtEpochSeconds = now.epochSecond,
                expiresAtEpochSeconds = expiresAt.epochSecond,
                nonce = UUID.randomUUID().toString().replace("-", "")
            )
        )

        val connectUrl = "$connectorPublicBaseUrl/api/connectors/apps/connect/$sessionId?state=${urlEncode(signedState)}"
        val session = ConnectorSessionInternal(
            id = sessionId,
            userId = userId,
            status = ConnectorSessionStatus.PENDING,
            providerInternal = PROVIDER_ZAPIER,
            connectUrl = connectUrl,
            callbackState = signedState,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            expiresAt = expiresAt.toString(),
            error = null,
            connectorId = null,
            enabledApps = DEFAULT_ENABLED_APPS,
            allowedAgents = emptyList(),
            provider = PROVIDER_ZAPIER,
            providerConfigKey = PROVIDER_ZAPIER,
            nangoSessionToken = "",
            requestedScopes = emptyList(),
            redirectUri = "",
            connectionId = null,
            accountId = null,
            agentId = null,
            returnPath = null
        )
        firestoreRepository.saveConnectorSession(session)
        logger.info("Legacy connector session started: sessionId=$sessionId userId=$userId provider=$PROVIDER_ZAPIER")
        return ConnectorSessionStartResponse(
            sessionId = sessionId,
            sessionToken = "",
            providerConfigKey = PROVIDER_ZAPIER,
            expiresAt = expiresAt.toString(),
            connectUrl = connectUrl
        )
    }

    override suspend fun getSessionConnectPage(sessionId: String, state: String): ConnectorConnectPage {
        ensureZapierCredentialConfigured()
        ensureSessionSigningConfigured()
        val embedId = ensureZapierEmbedConfigured()

        val payload = verifyState(state)
        if (payload.sessionId != sessionId) {
            throw IllegalArgumentException("Invalid state for session")
        }

        val session = firestoreRepository.getConnectorSession(sessionId)
            ?: throw NoSuchElementException("Connector session '$sessionId' not found")
        if (session.callbackState != state) {
            throw IllegalArgumentException("Invalid callback state")
        }
        if (!session.status.equals(ConnectorSessionStatus.PENDING, ignoreCase = true)) {
            throw IllegalStateException("Session '$sessionId' is not pending")
        }
        if (isSessionExpired(session)) {
            val expired = session.copy(
                status = ConnectorSessionStatus.EXPIRED,
                updatedAt = Instant.now().toString(),
                error = "Session expired"
            )
            firestoreRepository.saveConnectorSession(expired)
            throw IllegalStateException("Session '$sessionId' is expired")
        }

        val callbackUrl = "$connectorPublicBaseUrl/api/connectors/apps/callback"
        return ConnectorConnectPage(
            sessionId = sessionId,
            state = state,
            embedId = embedId,
            callbackUrl = callbackUrl
        )
    }

    override suspend fun finalizeConnectorCallback(state: String, mcpServerUrl: String?): ConnectorSessionCallbackResponse {
        ensureZapierCredentialConfigured()
        ensureSessionSigningConfigured()

        val payload = verifyState(state)
        val session = firestoreRepository.getConnectorSession(payload.sessionId)
            ?: throw NoSuchElementException("Connector session '${payload.sessionId}' not found")

        if (session.userId != payload.userId) {
            throw IllegalArgumentException("Session user mismatch")
        }
        if (session.callbackState != state) {
            throw IllegalArgumentException("Invalid callback state")
        }

        val normalized = normalizeSessionStatus(session)
        if (normalized.status == ConnectorSessionStatus.EXPIRED) {
            firestoreRepository.saveConnectorSession(normalized)
            return ConnectorSessionCallbackResponse(normalized.id, normalized.status, normalized.error)
        }
        if (!normalized.status.equals(ConnectorSessionStatus.PENDING, ignoreCase = true)) {
            return ConnectorSessionCallbackResponse(normalized.id, normalized.status, normalized.error)
        }

        val providedMcpServerUrl = mcpServerUrl?.trim().orEmpty()
        if (providedMcpServerUrl.isBlank()) {
            val failed = normalized.copy(
                status = ConnectorSessionStatus.FAILED,
                updatedAt = Instant.now().toString(),
                error = "Missing mcpServerUrl in callback"
            )
            firestoreRepository.saveConnectorSession(failed)
            return ConnectorSessionCallbackResponse(failed.id, failed.status, failed.error)
        }

        return try {
            connectZapierInternal(
                userId = normalized.userId,
                mcpServerUrl = providedMcpServerUrl,
                enabledApps = if (normalized.enabledApps.isEmpty()) DEFAULT_ENABLED_APPS else normalized.enabledApps,
                allowedAgents = normalized.allowedAgents
            )
            val completed = normalized.copy(
                status = ConnectorSessionStatus.COMPLETED,
                updatedAt = Instant.now().toString(),
                error = null,
                connectorId = PROVIDER_ZAPIER,
                accountId = PROVIDER_ZAPIER
            )
            firestoreRepository.saveConnectorSession(completed)
            ConnectorSessionCallbackResponse(completed.id, completed.status, null)
        } catch (e: Exception) {
            val failed = normalized.copy(
                status = ConnectorSessionStatus.FAILED,
                updatedAt = Instant.now().toString(),
                error = e.message ?: "Connector completion failed"
            )
            firestoreRepository.saveConnectorSession(failed)
            logger.error("Connector callback finalize failed: sessionId=${failed.id}", e)
            ConnectorSessionCallbackResponse(failed.id, failed.status, failed.error)
        }
    }

    override fun callbackRedirectUrl(callbackResponse: ConnectorSessionCallbackResponse): String? {
        val base = if (callbackResponse.status == ConnectorSessionStatus.COMPLETED) {
            callbackSuccessUrl
        } else {
            callbackFailureUrl
        }.ifBlank { return null }

        return URLBuilder(base).apply {
            parameters.append("sessionId", callbackResponse.sessionId)
            parameters.append("status", callbackResponse.status)
            callbackResponse.error?.takeIf { it.isNotBlank() }?.let { parameters.append("error", it) }
        }.buildString()
    }

    private suspend fun connectZapierInternal(
        userId: String,
        mcpServerUrl: String,
        enabledApps: List<String>,
        allowedAgents: List<String>
    ): ConnectorView {
        ensureZapierCredentialConfigured()
        val now = Instant.now().toString()
        val normalizedUrl = normalizeZapierUrl(mcpServerUrl)

        val existing = firestoreRepository.getUserConnectorInternal(userId, PROVIDER_ZAPIER)
        val connector = ConnectorInternal(
            provider = PROVIDER_ZAPIER,
            accountId = PROVIDER_ZAPIER,
            connectionId = "",
            providerConfigKey = "",
            status = ConnectorAccountStatus.CONNECTED,
            mcpServerUrl = normalizedUrl,
            enabledApps = normalizeEnabledApps(enabledApps),
            allowedAgents = normalizeAllowedAgents(allowedAgents),
            isDefaultForProvider = true,
            createdAt = existing?.createdAt?.ifBlank { now } ?: now,
            updatedAt = now,
            lastError = null
        )

        firestoreRepository.saveUserConnector(userId, connector)
        reconcileMcpForUser(userId)
        return connector.toLegacyView(hasCredential = true)
    }

    private suspend fun reconcileMcpForUser(userId: String) {
        ensureZapierCredentialConfigured()
        val droplet = firestoreRepository.getUserDropletInternal(userId) ?: return
        if (!droplet.status.equals("active", ignoreCase = true)) return

        val activeConnectors = firestoreRepository.listUserConnectorsInternal(userId)
            .filter { it.status.equals(ConnectorAccountStatus.CONNECTED, ignoreCase = true) }
            .filter { it.provider == PROVIDER_ZAPIER }
        val connectorTools = activeConnectors.mapNotNull { connectorToolName(it.provider) }

        val stableTools = droplet.configuredMcpTools.filterNot { it in CONNECTOR_TOOLS }
        val allTools = (stableTools + connectorTools).distinct()

        val runtimeConfigByTool = activeConnectors.mapNotNull { connector ->
            val toolName = connectorToolName(connector.provider) ?: return@mapNotNull null
            toolName to McpToolRuntimeConfig(upstreamOverride = connector.mcpServerUrl.ifBlank { null })
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
        val uri = try {
            URI(rawUrl.trim())
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

    private fun normalizeSessionStatus(session: ConnectorSessionInternal): ConnectorSessionInternal {
        if (!session.status.equals(ConnectorSessionStatus.PENDING, ignoreCase = true)) return session
        if (!isSessionExpired(session)) return session
        return session.copy(
            status = ConnectorSessionStatus.EXPIRED,
            updatedAt = Instant.now().toString(),
            error = "Session expired"
        )
    }

    private fun isSessionExpired(session: ConnectorSessionInternal): Boolean {
        val exp = runCatching { Instant.parse(session.expiresAt) }.getOrNull() ?: return true
        return Instant.now().isAfter(exp)
    }

    private fun signState(payload: SignedStatePayload): String {
        val payloadJson = stateJson.encodeToString(payload)
        val payloadB64 = base64UrlEncode(payloadJson.toByteArray(Charsets.UTF_8))
        val signatureBytes = hmacSha256(payloadB64.toByteArray(Charsets.UTF_8), sessionSigningSecret)
        val signatureB64 = base64UrlEncode(signatureBytes)
        return "$payloadB64.$signatureB64"
    }

    private fun verifyState(signedState: String): SignedStatePayload {
        val parts = signedState.split(".")
        if (parts.size != 2) throw IllegalArgumentException("Invalid callback state")
        val payloadB64 = parts[0]
        val signatureB64 = parts[1]
        val expected = hmacSha256(payloadB64.toByteArray(Charsets.UTF_8), sessionSigningSecret)
        val actual = runCatching { base64UrlDecode(signatureB64) }
            .getOrElse { throw IllegalArgumentException("Invalid callback state") }
        if (!java.security.MessageDigest.isEqual(actual, expected)) {
            throw IllegalArgumentException("Invalid callback state signature")
        }
        val payloadJson = String(base64UrlDecode(payloadB64), Charsets.UTF_8)
        return runCatching { stateJson.decodeFromString(SignedStatePayload.serializer(), payloadJson) }
            .getOrElse { throw IllegalArgumentException("Invalid callback state payload") }
    }

    private fun hmacSha256(input: ByteArray, secret: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(input)
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun hasZapierCredential(): Boolean = env("ZAPIER_MCP_EMBED_SECRET").isNotBlank()

    private fun ensureZapierCredentialConfigured() {
        if (!hasZapierCredential()) {
            throw IllegalStateException("ZAPIER_MCP_EMBED_SECRET is not configured")
        }
    }

    private fun ensureSessionSigningConfigured() {
        if (sessionSigningSecret.isBlank()) {
            throw IllegalStateException("CONNECTOR_SESSION_SIGNING_SECRET is not configured")
        }
    }

    private fun ensureZapierEmbedConfigured(): String {
        if (zapierEmbedId.isBlank()) {
            throw IllegalStateException("ZAPIER_MCP_EMBED_ID is not configured")
        }
        return zapierEmbedId
    }

    private suspend fun markSessionFailure(session: ConnectorSessionInternal?, error: String?) {
        if (session == null) return
        firestoreRepository.saveConnectorSession(
            session.copy(
                status = ConnectorSessionStatus.FAILED,
                updatedAt = Instant.now().toString(),
                error = error
            )
        )
    }

    private fun extractScopes(connection: NangoConnectionRecord, existing: ConnectorInternal?): List<String> {
        val rawScopes = connection.raw.objectValue("credentials").objectValue("raw").string("scope")
            ?: connection.raw.objectValue("credentials").string("scope")
            ?: existing?.grantedScopes?.joinToString(" ")
        return rawScopes
            ?.split(',', ' ')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: existing?.grantedScopes
            ?: emptyList()
    }

    private fun providerDisplayName(provider: String): String {
        return if (provider == PROVIDER_ZAPIER) {
            "Zapier"
        } else {
            nangoService.providers[provider]?.displayName ?: provider.replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun resolveConnectedAccount(userId: String, provider: String, accountId: String): ConnectorInternal {
        val normalizedProvider = provider.lowercase()
        val account = firestoreRepository.getUserConnectorInternalById(userId, accountId)
            ?: throw NoSuchElementException("Connector account '$accountId' not found")
        if (!account.provider.equals(normalizedProvider, ignoreCase = true)) {
            throw IllegalArgumentException("Connector account '$accountId' does not belong to provider '$normalizedProvider'")
        }
        if (!account.status.equals(ConnectorAccountStatus.CONNECTED, ignoreCase = true)) {
            throw IllegalStateException("Connector account '$accountId' is not connected")
        }
        return account
    }

    private fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""
}

@Serializable
private data class SignedStatePayload(
    val sessionId: String,
    val userId: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val nonce: String
)

private fun ConnectorInternal.toAccountView(): ConnectorAccountView = ConnectorAccountView(
    provider = provider,
    accountId = accountId.ifBlank { provider.lowercase() },
    connectionId = connectionId,
    providerConfigKey = providerConfigKey,
    externalAccountId = externalAccountId,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
    status = status,
    grantedScopes = grantedScopes,
    allowedAgents = allowedAgents,
    isDefaultForProvider = isDefaultForProvider,
    defaultForAgents = defaultForAgents,
    lastValidatedAt = lastValidatedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastError = lastError
)

private fun ConnectorInternal.toLegacyView(hasCredential: Boolean): ConnectorView {
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

private fun JsonObject.string(key: String): String? = this[key]?.jsonObjectOrPrimitiveContent()

private fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()

private fun JsonObject.objectValue(key: String): JsonObject =
    this[key]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

private fun JsonObject.stringMap(): Map<String, String> =
    entries.mapNotNull { (key, value) -> value.jsonObjectOrPrimitiveContent()?.let { key to it } }.toMap()

private fun JsonObject.errorDescription(): String? =
    objectValue("error").string("description") ?: objectValue("error").string("type")

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrPrimitiveContent(): String? =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()
