package com.suprbeta.admin

import kotlinx.serialization.Serializable

enum class AdminUserScope(val value: String) {
    PROVISIONED("provisioned"),
    ALL("all");

    companion object {
        fun fromQuery(raw: String?): AdminUserScope {
            val normalized = raw?.trim()?.lowercase() ?: return PROVISIONED
            return entries.firstOrNull { it.value == normalized } ?: PROVISIONED
        }
    }
}

@Serializable
data class AdminFirebaseConfigResponse(
    val configured: Boolean,
    val missing: List<String>,
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val appId: String,
    val messagingSenderId: String? = null,
    val storageBucket: String? = null,
    val measurementId: String? = null
)

@Serializable
data class AdminUsersResponse(
    val scope: String,
    val count: Int,
    val weekStartUtc: String,
    val weekEndUtc: String,
    val users: List<AdminUserRecord>
)

@Serializable
data class AdminUserRecord(
    val userId: String,
    val hasContainer: Boolean,
    val canDelete: Boolean,
    val containerStatus: String? = null,
    val containerUrl: String? = null,
    val proxyUrl: String? = null,
    val deploymentMode: String? = null,
    val dropletId: Long? = null,
    val hostId: Long? = null,
    val containerId: String? = null,
    val containerCreatedAt: String? = null,
    val weeklyCreditsUsed: Long,
    val weeklyUsageEvents: Long
)

@Serializable
data class AdminMetricsResponse(
    val capturedAtUtc: String,
    val intervalSeconds: Int,
    val overall: AdminOverallMetrics,
    val hosts: List<AdminHostMetrics>
)

@Serializable
data class AdminOverallMetrics(
    val hostCount: Int,
    val containerCount: Int,
    val userCount: Int,
    val cpuPercent: Double,
    val memoryUsedBytes: Long,
    val memoryTotalBytes: Long,
    val memoryUsagePercent: Double,
    val networkRxBytes: Long,
    val networkTxBytes: Long
)

@Serializable
data class AdminHostMetrics(
    val hostId: Long,
    val hostIp: String,
    val hostStatus: String,
    val coreCount: Int? = null,
    val cpuPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val memoryUsagePercent: Double? = null,
    val networkRxBytes: Long? = null,
    val networkTxBytes: Long? = null,
    val error: String? = null,
    val containers: List<AdminContainerMetrics>
)

@Serializable
data class AdminContainerMetrics(
    val userId: String? = null,
    val containerId: String,
    val status: String,
    val cpuPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
    val networkRxBytes: Long? = null,
    val networkTxBytes: Long? = null
)
