package com.suprbeta.usage

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.RemoteConfigService
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import com.suprbeta.usage.CreditCalculator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Response model for user credits information (weekly)
 */
@Serializable
data class UserCreditsResponse(
    val userId: String,
    val weeklyLimit: Long,
    val usedThisWeek: Long,
    val remainingThisWeek: Long,
    val usagePercentage: Double,
    val tier: String,
    val weekStartUtc: String,
    val weekEndUtc: String
)

/**
 * Response model for daily credit usage entry
 */
@Serializable
data class DailyCreditEntry(
    val dayUtc: String,
    val credits: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val weightedTokens: Long,
    val usageEvents: Long
)

/**
 * Response model for weekly usage aggregation (with credits)
 */
@Serializable
data class WeeklyUsageResponse(
    val userId: String,
    val weekStartUtc: String,
    val weekEndUtc: String,
    val days: List<DailyCreditEntry>,
    val totalCredits: Long,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalWeightedTokens: Long,
    val totalUsageEvents: Long,
    val dailyAverage: Long,
    val peakDay: DailyCreditEntry?,
    val weeklyLimit: Long,
    val remainingWeekly: Long,
    val usagePercentage: Double
)

fun Application.configureUsageRoutes(
    firestoreRepository: FirestoreRepository,
    remoteConfigService: RemoteConfigService
) {
    routing {
        authenticated {
            route("/api/usage") {

                /**
                 * GET /api/usage/credits
                 * Returns the authenticated user's current weekly credit status
                 */
                get("/credits") {
                    val user = call.attributes[firebaseUserKey]
                    log.debug("Fetching weekly credits for user ${user.uid}")

                    try {
                        // Get user's tier from custom claims (default to "free")
                        val tier = (user.customClaims["tier"] as? String) ?: "free"
                        val weeklyLimit = remoteConfigService.getWeeklyCreditLimit(tier)

                        // Calculate current week range (Monday to Sunday)
                        val (weekStart, weekEnd) = getCurrentWeekRange()
                        
                        // Aggregate credits for the current week
                        var usedThisWeek = 0L
                        var currentDate = weekStart
                        while (!currentDate.isAfter(weekEnd)) {
                            usedThisWeek += firestoreRepository.getDailyCreditUsage(user.uid, currentDate.toString())
                            currentDate = currentDate.plusDays(1)
                        }

                        val remainingThisWeek = (weeklyLimit - usedThisWeek).coerceAtLeast(0)
                        val usagePercentage = if (weeklyLimit > 0) {
                            (usedThisWeek.toDouble() / weeklyLimit) * 100
                        } else 0.0

                        val response = UserCreditsResponse(
                            userId = user.uid,
                            weeklyLimit = weeklyLimit,
                            usedThisWeek = usedThisWeek,
                            remainingThisWeek = remainingThisWeek,
                            usagePercentage = usagePercentage,
                            tier = tier,
                            weekStartUtc = weekStart.toString(),
                            weekEndUtc = weekEnd.toString()
                        )

                        call.respond(HttpStatusCode.OK, response)
                    } catch (e: Exception) {
                        log.error("Error fetching weekly credits for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/usage/week
                 * Returns the authenticated user's detailed usage for the current week
                 */
                get("/week") {
                    val user = call.attributes[firebaseUserKey]
                    log.debug("Fetching weekly usage for user ${user.uid}")

                    try {
                        val tier = (user.customClaims["tier"] as? String) ?: "free"
                        val weeklyLimit = remoteConfigService.getWeeklyCreditLimit(tier)
                        val (weekStart, weekEnd) = getCurrentWeekRange()

                        // Aggregate weekly usage with credits
                        val dailyUsages = mutableListOf<DailyCreditEntry>()
                        var totalCredits = 0L
                        var totalPromptTokens = 0L
                        var totalCompletionTokens = 0L
                        var totalWeightedTokens = 0L
                        var totalUsageEvents = 0L
                        var peakDay: DailyCreditEntry? = null

                        var currentDate = weekStart
                        while (!currentDate.isAfter(weekEnd)) {
                            val dayStr = currentDate.toString()
                            val usage = firestoreRepository.getDailyUsageDetail(user.uid, dayStr)

                            if (usage != null) {
                                val dayCredits = usage.credits
                                val weightedTokens = CreditCalculator.toWeightedTokens(usage)
                                
                                val entry = DailyCreditEntry(
                                    dayUtc = dayStr,
                                    credits = dayCredits,
                                    promptTokens = usage.promptTokens,
                                    completionTokens = usage.completionTokens,
                                    weightedTokens = weightedTokens,
                                    usageEvents = usage.usageEvents
                                )
                                dailyUsages.add(entry)
                                
                                totalCredits += dayCredits
                                totalPromptTokens += usage.promptTokens
                                totalCompletionTokens += usage.completionTokens
                                totalWeightedTokens += weightedTokens
                                totalUsageEvents += usage.usageEvents

                                // Track peak day
                                if (peakDay == null || dayCredits > peakDay.credits) {
                                    peakDay = entry
                                }
                            }

                            currentDate = currentDate.plusDays(1)
                        }

                        val dayCount = dailyUsages.size.coerceAtLeast(1)
                        val dailyAverage = totalCredits / dayCount
                        val remainingWeekly = (weeklyLimit - totalCredits).coerceAtLeast(0)
                        val usagePercentage = if (weeklyLimit > 0) {
                            (totalCredits.toDouble() / weeklyLimit) * 100
                        } else 0.0

                        val response = WeeklyUsageResponse(
                            userId = user.uid,
                            weekStartUtc = weekStart.toString(),
                            weekEndUtc = weekEnd.toString(),
                            days = dailyUsages,
                            totalCredits = totalCredits,
                            totalPromptTokens = totalPromptTokens,
                            totalCompletionTokens = totalCompletionTokens,
                            totalWeightedTokens = totalWeightedTokens,
                            totalUsageEvents = totalUsageEvents,
                            dailyAverage = dailyAverage,
                            peakDay = peakDay,
                            weeklyLimit = weeklyLimit,
                            remainingWeekly = remainingWeekly,
                            usagePercentage = usagePercentage
                        )

                        call.respond(HttpStatusCode.OK, response)
                    } catch (e: Exception) {
                        log.error("Error fetching weekly usage for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/usage/history?weeks={weeks}
                 * Returns the authenticated user's usage history for the specified number of weeks (default: 4, max: 12)
                 */
                get("/history") {
                    val user = call.attributes[firebaseUserKey]
                    val weeksParam = call.request.queryParameters["weeks"] ?: "4"
                    val weeks = weeksParam.toIntOrNull()?.coerceIn(1, 12) ?: 4

                    log.debug("Fetching usage history for user ${user.uid} for last $weeks weeks")

                    try {
                        val tier = (user.customClaims["tier"] as? String) ?: "free"
                        val weeklyLimit = remoteConfigService.getWeeklyCreditLimit(tier)

                        // Calculate weeks
                        val today = LocalDate.now(ZoneOffset.UTC)
                        val weekHistory = mutableListOf<WeeklyUsageResponse>()

                        for (weekOffset in 0 until weeks) {
                            val weekEnd = today.minusDays((weekOffset * 7).toLong())
                            val dayOfWeek = weekEnd.dayOfWeek.value
                            val weekStart = weekEnd.minusDays((dayOfWeek - 1).toLong())

                            // Aggregate this week's usage with credits
                            val dailyUsages = mutableListOf<DailyCreditEntry>()
                            var totalCredits = 0L
                            var totalPromptTokens = 0L
                            var totalCompletionTokens = 0L
                            var totalWeightedTokens = 0L
                            var totalUsageEvents = 0L
                            var peakDay: DailyCreditEntry? = null

                            var currentDate = weekStart
                            while (!currentDate.isAfter(weekEnd)) {
                                val dayStr = currentDate.toString()
                                val usage = firestoreRepository.getDailyUsageDetail(user.uid, dayStr)

                                if (usage != null) {
                                    val dayCredits = usage.credits
                                    val weightedTokens = CreditCalculator.toWeightedTokens(usage)
                                    
                                    val entry = DailyCreditEntry(
                                        dayUtc = dayStr,
                                        credits = dayCredits,
                                        promptTokens = usage.promptTokens,
                                        completionTokens = usage.completionTokens,
                                        weightedTokens = weightedTokens,
                                        usageEvents = usage.usageEvents
                                    )
                                    dailyUsages.add(entry)
                                    
                                    totalCredits += dayCredits
                                    totalPromptTokens += usage.promptTokens
                                    totalCompletionTokens += usage.completionTokens
                                    totalWeightedTokens += weightedTokens
                                    totalUsageEvents += usage.usageEvents

                                    if (peakDay == null || dayCredits > peakDay.credits) {
                                        peakDay = entry
                                    }
                                }

                                currentDate = currentDate.plusDays(1)
                            }

                            if (dailyUsages.isNotEmpty()) {
                                val dayCount = dailyUsages.size.coerceAtLeast(1)
                                val dailyAverage = totalCredits / dayCount
                                val remainingWeekly = (weeklyLimit - totalCredits).coerceAtLeast(0)
                                val usagePercentage = if (weeklyLimit > 0) {
                                    (totalCredits.toDouble() / weeklyLimit) * 100
                                } else 0.0

                                weekHistory.add(
                                    WeeklyUsageResponse(
                                        userId = user.uid,
                                        weekStartUtc = weekStart.toString(),
                                        weekEndUtc = weekEnd.toString(),
                                        days = dailyUsages,
                                        totalCredits = totalCredits,
                                        totalPromptTokens = totalPromptTokens,
                                        totalCompletionTokens = totalCompletionTokens,
                                        totalWeightedTokens = totalWeightedTokens,
                                        totalUsageEvents = totalUsageEvents,
                                        dailyAverage = dailyAverage,
                                        peakDay = peakDay,
                                        weeklyLimit = weeklyLimit,
                                        remainingWeekly = remainingWeekly,
                                        usagePercentage = usagePercentage
                                    )
                                )
                            }
                        }

                        call.respond(HttpStatusCode.OK, weekHistory)
                    } catch (e: Exception) {
                        log.error("Error fetching usage history for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/usage/day/{day}
                 * Returns the authenticated user's usage for a specific day (YYYY-MM-DD format)
                 */
                get("/day/{day}") {
                    val user = call.attributes[firebaseUserKey]
                    val dayParam = call.parameters["day"]

                    if (dayParam.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Day parameter is required (YYYY-MM-DD format)"))
                        return@get
                    }

                    // Validate date format
                    try {
                        LocalDate.parse(dayParam)
                    } catch (e: DateTimeParseException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid date format. Use YYYY-MM-DD"))
                        return@get
                    }

                    log.debug("Fetching usage for user ${user.uid} on day $dayParam")

                    try {
                        val usage = firestoreRepository.getDailyUsageDetail(user.uid, dayParam)

                        if (usage != null) {
                            call.respond(HttpStatusCode.OK, usage)
                        } else {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "userId" to user.uid,
                                    "dayUtc" to dayParam,
                                    "message" to "No usage recorded for this day"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        log.error("Error fetching usage for user ${user.uid} on day $dayParam", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }
            }
        }
    }
}

private fun currentDayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

/**
 * Returns the current week range (Monday to Sunday) in UTC
 */
private fun getCurrentWeekRange(): Pair<LocalDate, LocalDate> {
    val today = LocalDate.now(ZoneOffset.UTC)
    // Monday is 1, Sunday is 7 in ISO
    val dayOfWeek = today.dayOfWeek.value
    val weekStart = today.minusDays((dayOfWeek - 1).toLong()) // Monday
    val weekEnd = weekStart.plusDays(6) // Sunday
    return Pair(weekStart, weekEnd)
}
