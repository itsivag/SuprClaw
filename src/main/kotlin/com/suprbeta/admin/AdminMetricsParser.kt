package com.suprbeta.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong

object AdminMetricsParser {
    private val json = Json { ignoreUnknownKeys = true }

    data class DockerStatsRow(
        val containerId: String,
        val status: String,
        val cpuPercent: Double?,
        val memoryUsedBytes: Long?,
        val memoryLimitBytes: Long?,
        val networkRxBytes: Long?,
        val networkTxBytes: Long?
    )

    data class HostSnapshot(
        val coreCount: Int?,
        val cpuPercent: Double?,
        val memoryUsedBytes: Long?,
        val memoryTotalBytes: Long?,
        val memoryUsagePercent: Double?,
        val networkRxBytes: Long?,
        val networkTxBytes: Long?
    )

    fun parseDockerStats(raw: String): List<DockerStatsRow> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val containerId = obj["ID"]?.jsonPrimitive?.contentOrNull
                        ?: obj["Container"]?.jsonPrimitive?.contentOrNull
                        ?: return@runCatching null
                    val (memoryUsedBytes, memoryLimitBytes) = parseUsagePair(obj["MemUsage"]?.jsonPrimitive?.contentOrNull)
                    val (networkRxBytes, networkTxBytes) = parseIoPair(obj["NetIO"]?.jsonPrimitive?.contentOrNull)

                    DockerStatsRow(
                        containerId = containerId.trim(),
                        status = "running",
                        cpuPercent = parsePercent(obj["CPUPerc"]?.jsonPrimitive?.contentOrNull),
                        memoryUsedBytes = memoryUsedBytes,
                        memoryLimitBytes = memoryLimitBytes,
                        networkRxBytes = networkRxBytes,
                        networkTxBytes = networkTxBytes
                    )
                }.getOrNull()
            }
            .toList()
    }

    fun parseHostSnapshot(raw: String): HostSnapshot? {
        val pairs = raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains("=") }
            .map { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        if (pairs.isEmpty()) return null

        val coreCount = pairs["CORE_COUNT"]?.toIntOrNull()
        val cpuPercent = parsePercent(pairs["CPU_PERCENT"])
        val memoryTotalBytes = pairs["MEM_TOTAL_BYTES"]?.toLongOrNull()
        val memoryAvailableBytes = pairs["MEM_AVAILABLE_BYTES"]?.toLongOrNull()
        val memoryUsedBytes = if (memoryTotalBytes != null && memoryAvailableBytes != null) {
            (memoryTotalBytes - memoryAvailableBytes).coerceAtLeast(0L)
        } else {
            null
        }
        val memoryUsagePercent = if (memoryUsedBytes != null && memoryTotalBytes != null && memoryTotalBytes > 0) {
            (memoryUsedBytes.toDouble() / memoryTotalBytes.toDouble()) * 100.0
        } else {
            null
        }

        return HostSnapshot(
            coreCount = coreCount,
            cpuPercent = cpuPercent,
            memoryUsedBytes = memoryUsedBytes,
            memoryTotalBytes = memoryTotalBytes,
            memoryUsagePercent = memoryUsagePercent,
            networkRxBytes = pairs["NET_RX_BYTES"]?.toLongOrNull(),
            networkTxBytes = pairs["NET_TX_BYTES"]?.toLongOrNull()
        )
    }

    fun parsePercent(raw: String?): Double? {
        val cleaned = raw?.trim()
            ?.removeSuffix("%")
            ?.replace(",", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return cleaned.toDoubleOrNull()
    }

    fun parseUsagePair(raw: String?): Pair<Long?, Long?> {
        val input = raw?.trim().orEmpty()
        if (input.isBlank()) return null to null
        val parts = input.split("/")
        if (parts.size < 2) return null to null
        return parseSizeToBytes(parts[0]) to parseSizeToBytes(parts[1])
    }

    fun parseIoPair(raw: String?): Pair<Long?, Long?> {
        val input = raw?.trim().orEmpty()
        if (input.isBlank()) return null to null
        val parts = input.split("/")
        if (parts.size < 2) return null to null
        return parseSizeToBytes(parts[0]) to parseSizeToBytes(parts[1])
    }

    fun parseSizeToBytes(raw: String?): Long? {
        val input = raw?.trim()?.replace(",", "") ?: return null
        if (input.isBlank()) return null

        val normalized = input.lowercase()
        if (normalized == "n/a" || normalized == "--") return null

        val regex = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)?$""")
        val match = regex.find(input) ?: return null
        val numeric = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.uppercase().orEmpty().ifBlank { "B" }

        val multiplier = when (unit) {
            "B" -> 1.0
            "K", "KB" -> 1_000.0
            "M", "MB" -> 1_000_000.0
            "G", "GB" -> 1_000_000_000.0
            "T", "TB" -> 1_000_000_000_000.0
            "KIB" -> 1_024.0
            "MIB" -> 1_048_576.0
            "GIB" -> 1_073_741_824.0
            "TIB" -> 1_099_511_627_776.0
            else -> return null
        }

        return (numeric * multiplier).roundToLong()
    }
}
