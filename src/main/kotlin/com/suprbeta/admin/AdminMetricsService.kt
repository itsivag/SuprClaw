package com.suprbeta.admin

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.docker.models.HostInfo
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.server.application.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

class AdminMetricsService(
    private val firestoreRepository: FirestoreRepository,
    private val sshCommandExecutor: SshCommandExecutor,
    application: Application
) {
    private val log = application.log

    companion object {
        private const val METRICS_INTERVAL_SECONDS = 10
        private const val STATUS_RUNNING = "running"
        private const val STATUS_MISSING = "missing"
    }

    suspend fun collectMetrics(): AdminMetricsResponse = coroutineScope {
        val capturedAt = Instant.now().toString()
        val hosts = firestoreRepository.listHosts()
            .filter { it.status == HostInfo.STATUS_ACTIVE || it.status == HostInfo.STATUS_FULL }

        val dockerAssignmentsByHost = buildDockerAssignments()
            .groupBy { it.hostId }

        val hostMetrics = hosts.map { host ->
            async {
                collectHostMetrics(host, dockerAssignmentsByHost[host.hostId].orEmpty())
            }
        }.map { it.await() }

        val overall = aggregateOverall(hostMetrics)
        AdminMetricsResponse(
            capturedAtUtc = capturedAt,
            intervalSeconds = METRICS_INTERVAL_SECONDS,
            overall = overall,
            hosts = hostMetrics.sortedBy { it.hostId }
        )
    }

    private suspend fun buildDockerAssignments(): List<DockerAssignment> {
        return firestoreRepository.listAllUserDropletsInternal()
            .filter { it.userId.isNotBlank() && it.isDockerDeployment() && it.dropletId > 0L }
            .mapNotNull { droplet ->
                val containerId = droplet.containerIdOrNull() ?: droplet.dropletName.trim().takeIf { it.isNotBlank() }
                val hostId = droplet.dropletId
                if (containerId.isNullOrBlank() || hostId <= 0L) return@mapNotNull null
                DockerAssignment(
                    userId = droplet.userId,
                    hostId = hostId,
                    containerId = containerId.lowercase()
                )
            }
    }

    private fun collectHostMetrics(host: HostInfo, assignments: List<DockerAssignment>): AdminHostMetrics {
        val errors = mutableListOf<String>()

        val hostSnapshot = runCatching {
            val raw = sshCommandExecutor.runSshCommand(host.hostIp, hostSnapshotCommand())
            AdminMetricsParser.parseHostSnapshot(raw)
        }.onFailure {
            log.warn("Failed to collect host metrics for host ${host.hostId}: ${it.message}")
            errors += "host snapshot unavailable"
        }.getOrNull()

        val dockerRows = runCatching {
            val raw = sshCommandExecutor.runSshCommand(host.hostIp, dockerStatsCommand())
            AdminMetricsParser.parseDockerStats(raw)
        }.onFailure {
            log.warn("Failed to collect docker stats for host ${host.hostId}: ${it.message}")
            errors += "docker stats unavailable"
        }.getOrElse { emptyList() }

        val matchedAssignments = mutableSetOf<String>()
        val containers = dockerRows.map { row ->
            val assignment = resolveAssignment(assignments, row.containerId)
            if (assignment != null) {
                matchedAssignments += assignment.containerId
            }
            AdminContainerMetrics(
                userId = assignment?.userId,
                containerId = row.containerId,
                status = row.status.ifBlank { STATUS_RUNNING },
                cpuPercent = row.cpuPercent,
                memoryUsedBytes = row.memoryUsedBytes,
                memoryLimitBytes = row.memoryLimitBytes,
                networkRxBytes = row.networkRxBytes,
                networkTxBytes = row.networkTxBytes
            )
        }.toMutableList()

        val unmatchedAssignments = assignments.filterNot { matchedAssignments.contains(it.containerId) }
        unmatchedAssignments.forEach { assignment ->
            containers += AdminContainerMetrics(
                userId = assignment.userId,
                containerId = assignment.containerId,
                status = STATUS_MISSING
            )
        }

        return AdminHostMetrics(
            hostId = host.hostId,
            hostIp = host.hostIp,
            hostStatus = host.status,
            coreCount = hostSnapshot?.coreCount,
            cpuPercent = hostSnapshot?.cpuPercent,
            memoryUsedBytes = hostSnapshot?.memoryUsedBytes,
            memoryTotalBytes = hostSnapshot?.memoryTotalBytes,
            memoryUsagePercent = hostSnapshot?.memoryUsagePercent,
            networkRxBytes = hostSnapshot?.networkRxBytes,
            networkTxBytes = hostSnapshot?.networkTxBytes,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            containers = containers.sortedWith(compareBy({ it.userId ?: "" }, { it.containerId }))
        )
    }

    private fun resolveAssignment(assignments: List<DockerAssignment>, containerIdRaw: String): DockerAssignment? {
        val containerId = containerIdRaw.trim().lowercase()
        if (containerId.isBlank()) return null
        return assignments.firstOrNull { assignment ->
            assignment.containerId == containerId ||
                assignment.containerId.startsWith(containerId) ||
                containerId.startsWith(assignment.containerId)
        }
    }

    private fun aggregateOverall(hosts: List<AdminHostMetrics>): AdminOverallMetrics {
        var weightedCpuTotal = 0.0
        var cpuCoreWeight = 0
        var memoryUsedTotal = 0L
        var memoryCapacityTotal = 0L
        var networkRxTotal = 0L
        var networkTxTotal = 0L
        val uniqueContainerIds = mutableSetOf<String>()
        val uniqueUsers = mutableSetOf<String>()

        hosts.forEach { host ->
            val cores = host.coreCount ?: 1
            val cpu = host.cpuPercent
            if (cpu != null) {
                weightedCpuTotal += cpu * cores.toDouble()
                cpuCoreWeight += cores
            }

            memoryUsedTotal += host.memoryUsedBytes ?: 0L
            memoryCapacityTotal += host.memoryTotalBytes ?: 0L
            networkRxTotal += host.networkRxBytes ?: 0L
            networkTxTotal += host.networkTxBytes ?: 0L

            host.containers.forEach { container ->
                uniqueContainerIds += container.containerId.lowercase()
                container.userId?.let { uniqueUsers += it }
            }
        }

        val cpuPercent = if (cpuCoreWeight > 0) weightedCpuTotal / cpuCoreWeight.toDouble() else 0.0
        val memoryUsagePercent = if (memoryCapacityTotal > 0) {
            (memoryUsedTotal.toDouble() / memoryCapacityTotal.toDouble()) * 100.0
        } else {
            0.0
        }

        return AdminOverallMetrics(
            hostCount = hosts.size,
            containerCount = uniqueContainerIds.size,
            userCount = uniqueUsers.size,
            cpuPercent = cpuPercent,
            memoryUsedBytes = memoryUsedTotal,
            memoryTotalBytes = memoryCapacityTotal,
            memoryUsagePercent = memoryUsagePercent,
            networkRxBytes = networkRxTotal,
            networkTxBytes = networkTxTotal
        )
    }

    private fun dockerStatsCommand(): String =
        "docker stats --no-stream --format '{{json .}}' 2>/dev/null || true"

    private fun hostSnapshotCommand(): String {
        val d = '$'
        return """
            read -r _ u1 n1 s1 i1 w1 irq1 sirq1 st1 _ _ < /proc/stat
            idle1=${d}((i1+w1))
            total1=${d}((u1+n1+s1+i1+w1+irq1+sirq1+st1))
            sleep 0.2
            read -r _ u2 n2 s2 i2 w2 irq2 sirq2 st2 _ _ < /proc/stat
            idle2=${d}((i2+w2))
            total2=${d}((u2+n2+s2+i2+w2+irq2+sirq2+st2))
            dt=${d}((total2-total1))
            di=${d}((idle2-idle1))
            if [ "${d}dt" -gt 0 ]; then
              usage_tenths=${d}(( (1000*(dt-di))/dt ))
              cpu_pct="${d}((usage_tenths/10)).${d}((usage_tenths%10))"
            else
              cpu_pct="0.0"
            fi
            mem_total_kb=${d}(grep '^MemTotal:' /proc/meminfo | tr -s ' ' | cut -d' ' -f2)
            mem_avail_kb=${d}(grep '^MemAvailable:' /proc/meminfo | tr -s ' ' | cut -d' ' -f2)
            mem_total=${d}((mem_total_kb*1024))
            mem_avail=${d}((mem_avail_kb*1024))
            rx=0
            tx=0
            while IFS= read -r line; do
              iface=${d}(echo "${d}line" | cut -d: -f1 | xargs)
              [ "${d}iface" = "lo" ] && continue
              data=${d}(echo "${d}line" | cut -d: -f2)
              r=${d}(echo "${d}data" | awk '{print ${d}1}')
              t=${d}(echo "${d}data" | awk '{print ${d}9}')
              rx=${d}((rx+r))
              tx=${d}((tx+t))
            done < /proc/net/dev
            cores=${d}(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
            echo "CPU_PERCENT=${d}cpu_pct"
            echo "MEM_TOTAL_BYTES=${d}mem_total"
            echo "MEM_AVAILABLE_BYTES=${d}mem_avail"
            echo "NET_RX_BYTES=${d}rx"
            echo "NET_TX_BYTES=${d}tx"
            echo "CORE_COUNT=${d}cores"
        """.trimIndent()
    }

    private data class DockerAssignment(
        val userId: String,
        val hostId: Long,
        val containerId: String
    )
}
