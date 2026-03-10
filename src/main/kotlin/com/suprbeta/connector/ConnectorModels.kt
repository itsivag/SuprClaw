package com.suprbeta.connector

import kotlinx.serialization.Serializable

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
data class ConnectZapierRequest(
    val mcpServerUrl: String,
    val enabledApps: List<String> = listOf("gmail", "calendar", "drive", "docs"),
    val allowedAgents: List<String> = emptyList()
)

@Serializable
data class UpdateConnectorPolicyRequest(
    val allowedAgents: List<String> = emptyList()
)

@Serializable
data class ZapierEmbedConfigResponse(
    val embedId: String,
    val scriptUrl: String = "https://mcp.zapier.com/embed/v1/mcp.js"
)
