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
    
    // In-memory cache for fast lookups per tier
    private val cachedLimits = mapOf(
        "free" to AtomicLong(100_000L), // Default 100k credits
        "pro"  to AtomicLong(1_000_000L), // Default 1M credits
        "max"  to AtomicLong(5_000_000L)  // Default 5M credits
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
            
            // Fetch limits for all tiers
            for ((tier, atomicLimit) in cachedLimits) {
                val paramName = "daily_credit_limit_$tier"
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
     * Gets the daily credit limit for a specific user tier.
     * Falls back to the 'free' tier limit if the requested tier is unknown.
     */
    fun getDailyCreditLimit(tier: String): Long {
        val normalizedTier = tier.lowercase().trim()
        val limit = cachedLimits[normalizedTier] ?: cachedLimits["free"]!!
        return limit.get()
    }
}
