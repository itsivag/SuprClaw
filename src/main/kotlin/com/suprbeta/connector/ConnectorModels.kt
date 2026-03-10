package com.suprbeta.connector

import kotlinx.serialization.Serializable

object ConnectorSessionStatus {
    const val PENDING = "pending"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}

@Serializable
data class ConnectorInternal(
    val provider: String = "",
    val status: String = "connected",
    val mcpServerUrl: String = "",
    val enabledApps: List<String> = emptyList(),
    val allowedAgents: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastError: String? = null
) {
    constructor() : this("", "connected", "", emptyList(), emptyList(), "", "", null)
}

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

@Serializable
data class ConnectorListResponse(
    val connectors: List<ConnectorView>
)

@Serializable
data class ConnectorSessionStartResponse(
    val connectUrl: String,
    val sessionId: String
)

@Serializable
data class ConnectorSessionStatusResponse(
    val sessionId: String,
    val status: String,
    val error: String? = null
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
    val allowedAgents: List<String> = emptyList()
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
        allowedAgents = emptyList()
    )
}

@Serializable
data class UpdateConnectorPolicyRequest(
    val allowedAgents: List<String> = emptyList()
)
