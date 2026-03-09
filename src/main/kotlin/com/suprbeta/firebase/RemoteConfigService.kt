package com.suprbeta.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.ktor.server.application.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

class RemoteConfigService(
    private val application: Application,
    private val firebaseApp: FirebaseApp
) {
    private val logger = application.log
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // In-memory cache for fast lookups per tier (weekly credit limits)
    // Credits = (inputTokens * 1 + outputTokens * 2) / 1000
    // Example: 1000 input + 500 output tokens = (1000*1 + 500*2)/1000 = 2 credits
    private val cachedLimits = mapOf(
        "free" to AtomicLong(1_000L),   // ~500K-1M tokens/week depending on input/output mix
        "pro"  to AtomicLong(10_000L),  // ~5M-10M tokens/week
        "max"  to AtomicLong(50_000L)   // ~25M-50M tokens/week
    )
    
    init {
        // Start background polling
        scope.launch {
            while (isActive) {
                fetchTemplate()
                delay(10.minutes)
            }
        }
    }

    private suspend fun fetchTemplate() {
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance(firebaseApp)
            val template = withContext(Dispatchers.IO) {
                remoteConfig.templateAsync.get()
            }
            
            // Fetch weekly limits for all tiers
            for ((tier, atomicLimit) in cachedLimits) {
                val paramName = "weekly_credit_limit_$tier"
                val param = template.parameters[paramName]
                val explicitDefault = param?.defaultValue as? com.google.firebase.remoteconfig.ParameterValue.Explicit
                val limitStr = explicitDefault?.value
                val limit = limitStr?.toLongOrNull()
                
                if (limit != null) {
                    if (atomicLimit.getAndSet(limit) != limit) {
                        logger.info("Updated $paramName from Remote Config: $limit")
                    }
                } else {
                    logger.debug("$paramName not found or invalid in Remote Config, keeping current: ${atomicLimit.get()}")
                }
            }
            
        } catch (e: Exception) {
            logger.warn("Failed to fetch Remote Config template: ${e.message}")
        }
    }

    /**
     * Gets the weekly credit limit for a specific user tier.
     * Falls back to the 'free' tier limit if the requested tier is unknown.
     */
    fun getWeeklyCreditLimit(tier: String): Long {
        val normalizedTier = tier.lowercase().trim()
        val limit = cachedLimits[normalizedTier] ?: cachedLimits["free"]!!
        return limit.get()
    }
}
