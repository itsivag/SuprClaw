package com.suprbeta.connector

import com.suprbeta.digitalocean.DropletMcpService
import com.suprbeta.digitalocean.McpToolRuntimeConfig
import com.suprbeta.firebase.FirestoreRepository
import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface ConnectorService {
    suspend fun listConnectors(userId: String): List<ConnectorView>
    suspend fun updateConnectorPolicy(userId: String, provider: String, allowedAgents: List<String>): ConnectorView
    suspend fun disconnectConnector(userId: String, provider: String)

    suspend fun startConnectorSession(userId: String): ConnectorSessionStartResponse
    suspend fun getConnectorSessionStatus(userId: String, sessionId: String): ConnectorSessionStatusResponse
    suspend fun resolveSessionConnectRedirect(sessionId: String, state: String): String
    suspend fun finalizeConnectorCallback(state: String, mcpServerUrl: String?): ConnectorSessionCallbackResponse
    fun callbackRedirectUrl(callbackResponse: ConnectorSessionCallbackResponse): String?
}

class ConnectorServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val dropletMcpService: DropletMcpService,
    application: Application
) : ConnectorService {
    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    private val stateJson = Json { ignoreUnknownKeys = true }

    companion object {
        private const val PROVIDER_ZAPIER = "zapier"
        private val CONNECTOR_TOOLS = setOf(PROVIDER_ZAPIER)
        private const val DEFAULT_CONNECTOR_BASE_URL = "https://api.suprclaw.com"
        private const val DEFAULT_SESSION_TTL_SECONDS = 900L
        private val DEFAULT_ENABLED_APPS = listOf("gmail", "calendar", "drive", "docs")
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
    private val zapierConnectUrlTemplate: String by lazy { env("ZAPIER_MCP_CONNECT_URL_TEMPLATE") }

    override suspend fun listConnectors(userId: String): List<ConnectorView> {
        return firestoreRepository.listUserConnectorsInternal(userId)
            .map { it.toView(hasCredential = hasZapierCredential()) }
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

    override suspend fun startConnectorSession(userId: String): ConnectorSessionStartResponse {
        ensureZapierCredentialConfigured()
        ensureSessionSigningConfigured()

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
            allowedAgents = emptyList()
        )
        firestoreRepository.saveConnectorSession(session)
        logger.info("Connector session started: sessionId=$sessionId userId=$userId provider=$PROVIDER_ZAPIER")
        return ConnectorSessionStartResponse(connectUrl = connectUrl, sessionId = sessionId)
    }

    override suspend fun getConnectorSessionStatus(userId: String, sessionId: String): ConnectorSessionStatusResponse {
        val session = firestoreRepository.getConnectorSession(sessionId)
            ?: throw NoSuchElementException("Connector session '$sessionId' not found")
        if (session.userId != userId) throw NoSuchElementException("Connector session '$sessionId' not found")

        val normalized = normalizeSessionStatus(session)
        if (normalized != session) {
            firestoreRepository.saveConnectorSession(normalized)
        }
        return ConnectorSessionStatusResponse(sessionId = normalized.id, status = normalized.status, error = normalized.error)
    }

    override suspend fun resolveSessionConnectRedirect(sessionId: String, state: String): String {
        ensureZapierCredentialConfigured()
        ensureSessionSigningConfigured()

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
        val redirectUrl = buildZapierConnectRedirect(
            template = zapierConnectUrlTemplate,
            state = state,
            callbackUrl = callbackUrl,
            sessionId = sessionId
        )
        logger.info("Resolved connector redirect for sessionId=$sessionId")
        return redirectUrl
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
            return ConnectorSessionCallbackResponse(
                sessionId = normalized.id,
                status = normalized.status,
                error = normalized.error
            )
        }
        if (!normalized.status.equals(ConnectorSessionStatus.PENDING, ignoreCase = true)) {
            return ConnectorSessionCallbackResponse(
                sessionId = normalized.id,
                status = normalized.status,
                error = normalized.error
            )
        }

        val providedMcpServerUrl = mcpServerUrl?.trim().orEmpty()
        if (providedMcpServerUrl.isBlank()) {
            val failed = normalized.copy(
                status = ConnectorSessionStatus.FAILED,
                updatedAt = Instant.now().toString(),
                error = "Missing mcpServerUrl in callback"
            )
            firestoreRepository.saveConnectorSession(failed)
            logger.warn("Connector callback failed: missing mcpServerUrl sessionId=${failed.id}")
            return ConnectorSessionCallbackResponse(sessionId = failed.id, status = failed.status, error = failed.error)
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
                connectorId = PROVIDER_ZAPIER
            )
            firestoreRepository.saveConnectorSession(completed)
            logger.info("Connector session completed: sessionId=${completed.id} userId=${completed.userId}")
            ConnectorSessionCallbackResponse(sessionId = completed.id, status = completed.status, error = null)
        } catch (e: Exception) {
            val failed = normalized.copy(
                status = ConnectorSessionStatus.FAILED,
                updatedAt = Instant.now().toString(),
                error = e.message ?: "Connector completion failed"
            )
            firestoreRepository.saveConnectorSession(failed)
            logger.error("Connector callback finalize failed: sessionId=${failed.id}", e)
            ConnectorSessionCallbackResponse(sessionId = failed.id, status = failed.status, error = failed.error)
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
            status = "connected",
            mcpServerUrl = normalizedUrl,
            enabledApps = normalizeEnabledApps(enabledApps),
            allowedAgents = normalizeAllowedAgents(allowedAgents),
            createdAt = existing?.createdAt?.ifBlank { now } ?: now,
            updatedAt = now,
            lastError = null
        )

        firestoreRepository.saveUserConnector(userId, connector)
        reconcileMcpForUser(userId)
        logger.info("Connector '$PROVIDER_ZAPIER' connected for userId=$userId")
        return connector.toView(hasCredential = true)
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

    private fun buildZapierConnectRedirect(
        template: String,
        state: String,
        callbackUrl: String,
        sessionId: String
    ): String {
        if (template.isBlank()) {
            throw IllegalStateException("ZAPIER_MCP_CONNECT_URL_TEMPLATE is not configured")
        }
        val hasPlaceholders = template.contains("{state}") ||
            template.contains("{callbackUrl}") ||
            template.contains("{sessionId}")
        return if (hasPlaceholders) {
            template
                .replace("{state}", urlEncode(state))
                .replace("{callbackUrl}", urlEncode(callbackUrl))
                .replace("{sessionId}", urlEncode(sessionId))
        } else {
            URLBuilder(template).apply {
                parameters.append("state", state)
                parameters.append("callback_url", callbackUrl)
                parameters.append("session_id", sessionId)
            }.buildString()
        }
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
        val payload = runCatching { stateJson.decodeFromString(SignedStatePayload.serializer(), payloadJson) }
            .getOrElse { throw IllegalArgumentException("Invalid callback state payload") }
        return payload
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
