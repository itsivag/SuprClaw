package com.suprbeta.admin

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import java.time.LocalDate
import java.time.ZoneOffset

class AdminUserService(
    private val firestoreRepository: FirestoreRepository
) {
    suspend fun listUsers(scope: AdminUserScope): AdminUsersResponse {
        val dropletsByUser = firestoreRepository.listAllUserDropletsInternal()
            .filter { it.userId.isNotBlank() }
            .associateBy { it.userId }

        val userIds: List<String> = when (scope) {
            AdminUserScope.PROVISIONED -> dropletsByUser.keys.toList()
            AdminUserScope.ALL -> (firestoreRepository.listUserIds() + dropletsByUser.keys).toSet().toList()
        }.sorted()

        val (weekStart, weekEnd) = currentWeekRangeUtc()
        val users = userIds.map { userId ->
            val droplet = dropletsByUser[userId]
            val usage = aggregateWeekUsage(userId, weekStart, weekEnd)
            AdminUserRecord(
                userId = userId,
                hasContainer = droplet != null,
                canDelete = droplet != null,
                containerStatus = droplet?.status,
                containerUrl = droplet?.resolveAdminContainerUrl(),
                proxyUrl = droplet?.gatewayUrl?.ifBlank { null },
                deploymentMode = droplet?.deploymentMode?.ifBlank { null },
                dropletId = droplet?.dropletId?.takeIf { it > 0L },
                hostId = droplet?.dockerHostIdOrNull(),
                containerId = droplet?.containerIdOrNull(),
                containerCreatedAt = droplet?.createdAt?.ifBlank { null },
                weeklyCreditsUsed = usage.credits,
                weeklyUsageEvents = usage.usageEvents
            )
        }

        return AdminUsersResponse(
            scope = scope.value,
            count = users.size,
            weekStartUtc = weekStart.toString(),
            weekEndUtc = weekEnd.toString(),
            users = users
        )
    }

    private suspend fun aggregateWeekUsage(userId: String, weekStart: LocalDate, weekEnd: LocalDate): WeeklyUsageAggregate {
        var currentDate = weekStart
        var totalCredits = 0L
        var totalEvents = 0L

        while (!currentDate.isAfter(weekEnd)) {
            val usage = firestoreRepository.getDailyUsageDetail(userId, currentDate.toString())
            if (usage != null) {
                totalCredits += usage.credits
                totalEvents += usage.usageEvents
            }
            currentDate = currentDate.plusDays(1)
        }

        return WeeklyUsageAggregate(totalCredits, totalEvents)
    }

    private fun currentWeekRangeUtc(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val dayOfWeek = today.dayOfWeek.value
        val weekStart = today.minusDays((dayOfWeek - 1).toLong())
        val weekEnd = weekStart.plusDays(6)
        return weekStart to weekEnd
    }

    private fun UserDropletInternal.resolveAdminContainerUrl(): String? {
        val normalizedVpcUrl = vpsGatewayUrl.trim()
        if (normalizedVpcUrl.isNotBlank()) return normalizedVpcUrl

        val normalizedSubdomain = subdomain?.trim().orEmpty()
        if (normalizedSubdomain.isNotBlank()) return "https://$normalizedSubdomain"

        return gatewayUrl.trim().ifBlank { null }
    }

    private fun UserDropletInternal.dockerHostIdOrNull(): Long? {
        if (!isDockerDeployment()) return null
        return dropletId.takeIf { it > 0L }
    }

    private data class WeeklyUsageAggregate(
        val credits: Long,
        val usageEvents: Long
    )
}
