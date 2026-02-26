package com.suprbeta.marketplace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MarketplaceCatalog(
    val repo: String,
    val agents: List<MarketplaceAgent>
)

@Serializable
data class MarketplaceAgent(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("session_key") val sessionKey: String,
    @SerialName("is_lead") val isLead: Boolean = false,
    val capabilities: List<String> = emptyList(),
    @SerialName("best_with") val bestWith: List<String> = emptyList(),
    @SerialName("source_path") val sourcePath: String,
    @SerialName("install_path") val installPath: String,
    @SerialName("required_mcp_tools") val requiredMcpTools: List<String> = emptyList()
)
