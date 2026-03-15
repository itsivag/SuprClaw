package com.suprbeta.connector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object ConnectorSessionStatus {
    const val PENDING = "pending"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}

object ConnectorAccountStatus {
    const val CONNECTED = "connected"
    const val RECONNECT_REQUIRED = "reconnect_required"
    const val DISCONNECTED = "disconnected"
}

@Serializable
data class ConnectorInternal(
    val provider: String = "",
    val accountId: String = "",
    val connectionId: String = "",
    val providerConfigKey: String = "",
    val externalAccountId: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val status: String = ConnectorAccountStatus.CONNECTED,
    val grantedScopes: List<String> = emptyList(),
    val allowedAgents: List<String> = emptyList(),
    val isDefaultForProvider: Boolean = false,
    val defaultForAgents: List<String> = emptyList(),
    val lastValidatedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastError: String? = null,
    val mcpServerUrl: String = "",
    val enabledApps: List<String> = emptyList()
) {
    constructor() : this(
        provider = "",
        accountId = "",
        connectionId = "",
        providerConfigKey = "",
        externalAccountId = null,
        displayName = null,
        email = null,
        avatarUrl = null,
        status = ConnectorAccountStatus.CONNECTED,
        grantedScopes = emptyList(),
        allowedAgents = emptyList(),
        isDefaultForProvider = false,
        defaultForAgents = emptyList(),
        lastValidatedAt = null,
        createdAt = "",
        updatedAt = "",
        lastError = null,
        mcpServerUrl = "",
        enabledApps = emptyList()
    )
}

@Serializable
data class ConnectorAccountView(
    val provider: String,
    val accountId: String,
    val connectionId: String,
    val providerConfigKey: String,
    val externalAccountId: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val status: String,
    val grantedScopes: List<String>,
    val allowedAgents: List<String>,
    val isDefaultForProvider: Boolean,
    val defaultForAgents: List<String>,
    val lastValidatedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastError: String? = null
)

@Serializable
data class ConnectorProviderView(
    val provider: String,
    val providerDisplayName: String,
    val accounts: List<ConnectorAccountView>
)

@Serializable
data class ConnectorListResponse(
    val connectors: List<ConnectorProviderView>
)

@Serializable
data class ConnectorAccountsResponse(
    val provider: String,
    val accounts: List<ConnectorAccountView>
)

@Serializable
data class ConnectorToolDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ConnectorToolsResponse(
    val provider: String,
    val accountId: String,
    val providerConfigKey: String,
    val tools: List<ConnectorToolDefinition>
)

@Serializable
data class ConnectorActionInvokeRequest(
    val input: JsonElement? = null,
    val async: Boolean = false,
    val maxRetries: Int? = null
)

@Serializable
data class ConnectorActionInvokeResponse(
    val actionName: String,
    val async: Boolean,
    val output: JsonElement? = null,
    val actionId: String? = null,
    val statusUrl: String? = null
)

@Serializable
data class ConnectorSessionStartRequest(
    val scopePreset: String? = null,
    val agentId: String? = null,
    val returnPath: String? = null,
    val accountId: String? = null
)

@Serializable
data class ConnectorSessionStartResponse(
    val sessionId: String,
    val sessionToken: String,
    val providerConfigKey: String,
    val expiresAt: String,
    val connectUrl: String? = null
)

@Serializable
data class ConnectorSessionStatusResponse(
    val sessionId: String,
    val provider: String,
    val status: String,
    val error: String? = null,
    val account: ConnectorAccountView? = null
)

@Serializable
data class ConnectorSessionCallbackResponse(
    val sessionId: String,
    val status: String,
    val error: String? = null
)

data class ConnectorConnectPage(
    val sessionId: String,
    val state: String,
    val embedId: String,
    val callbackUrl: String
)

@Serializable
data class ConnectorSessionInternal(
    val id: String = "",
    val userId: String = "",
    val status: String = ConnectorSessionStatus.PENDING,
    val providerInternal: String = "zapier",
    val connectUrl: String = "",
    val callbackState: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val expiresAt: String = "",
    val error: String? = null,
    val connectorId: String? = null,
    val enabledApps: List<String> = emptyList(),
    val allowedAgents: List<String> = emptyList(),
    val provider: String = "",
    val providerConfigKey: String = "",
    val nangoSessionToken: String = "",
    val requestedScopes: List<String> = emptyList(),
    val redirectUri: String = "",
    val connectionId: String? = null,
    val accountId: String? = null,
    val agentId: String? = null,
    val returnPath: String? = null
) {
    constructor() : this(
        id = "",
        userId = "",
        status = ConnectorSessionStatus.PENDING,
        providerInternal = "zapier",
        connectUrl = "",
        callbackState = "",
        createdAt = "",
        updatedAt = "",
        expiresAt = "",
        error = null,
        connectorId = null,
        enabledApps = emptyList(),
        allowedAgents = emptyList(),
        provider = "",
        providerConfigKey = "",
        nangoSessionToken = "",
        requestedScopes = emptyList(),
        redirectUri = "",
        connectionId = null,
        accountId = null,
        agentId = null,
        returnPath = null
    )
}

@Serializable
data class UpdateConnectorPolicyRequest(
    val allowedAgents: List<String> = emptyList(),
    val isDefaultForProvider: Boolean? = null,
    val defaultForAgents: List<String>? = null
)

@Serializable
data class ConnectorView(
    val provider: String,
    val status: String,
    val enabledApps: List<String>,
    val allowedAgents: List<String>,
    val mcpHost: String,
    val hasCredential: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastError: String? = null
)
