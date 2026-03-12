package com.suprbeta.browser

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlin.math.max
import kotlin.random.Random

data class BrowserConfig(
    val enabled: Boolean,
    val apiBaseUrl: String,
    val publicBaseUrl: String,
    val firecrawlApiKey: String,
    val defaultTtlSeconds: Int,
    val defaultTakeoverTimeoutSeconds: Int,
    val minTakeoverTimeoutSeconds: Int,
    val maxTakeoverTimeoutSeconds: Int,
    val gracefulCloseMarginSeconds: Int,
    val keepaliveIntervalSeconds: Int,
    val heartbeatIntervalSeconds: Int,
    val staleHeartbeatSeconds: Int,
    val reconciliationIntervalSeconds: Int,
    val globalActiveSessionLimit: Int,
    val perUserActiveSessionLimit: Int,
    val retryAfterMinSeconds: Int,
    val retryAfterMaxSeconds: Int,
    val deleteConfirmTimeoutSeconds: Int,
    val deleteConfirmPollSeconds: Int
) {
    fun normalizedTakeoverTimeout(requestedSeconds: Int?): Int {
        val candidate = requestedSeconds ?: defaultTakeoverTimeoutSeconds
        return candidate.coerceIn(minTakeoverTimeoutSeconds, maxTakeoverTimeoutSeconds)
    }

    fun activityTtlSeconds(takeoverTimeoutSeconds: Int): Int {
        return max(defaultTtlSeconds, takeoverTimeoutSeconds + gracefulCloseMarginSeconds)
            .coerceAtMost(3600)
    }

    fun retryAfterSeconds(): Int = Random.nextInt(retryAfterMinSeconds, retryAfterMaxSeconds + 1)

    companion object {
        private const val DEFAULT_PUBLIC_BASE_URL = "https://api.suprclaw.com"
        private const val DEFAULT_API_BASE_URL = "https://api.firecrawl.dev"

        fun fromEnvironment(application: Application): BrowserConfig {
            val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
            fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""

            val publicBaseUrl = env("CLOUD_BROWSER_PUBLIC_BASE_URL")
                .ifBlank { env("CONNECTOR_PUBLIC_BASE_URL") }
                .ifBlank { env("WEBHOOK_BASE_URL") }
                .ifBlank { DEFAULT_PUBLIC_BASE_URL }
                .trimEnd('/')

            return BrowserConfig(
                enabled = !env("CLOUD_BROWSER_ENABLED").equals("false", ignoreCase = true),
                apiBaseUrl = env("FIRECRAWL_API_BASE_URL").ifBlank { DEFAULT_API_BASE_URL }.trimEnd('/'),
                publicBaseUrl = publicBaseUrl,
                firecrawlApiKey = env("FIRECRAWL_API_KEY"),
                defaultTtlSeconds = env("CLOUD_BROWSER_TTL_SECONDS").toIntOrNull()?.coerceIn(300, 3600) ?: 1800,
                defaultTakeoverTimeoutSeconds = env("CLOUD_BROWSER_TAKEOVER_TIMEOUT_SECONDS").toIntOrNull()?.coerceIn(300, 1800) ?: 600,
                minTakeoverTimeoutSeconds = env("CLOUD_BROWSER_MIN_TAKEOVER_TIMEOUT_SECONDS").toIntOrNull()?.coerceAtLeast(300) ?: 300,
                maxTakeoverTimeoutSeconds = env("CLOUD_BROWSER_MAX_TAKEOVER_TIMEOUT_SECONDS").toIntOrNull()?.coerceIn(300, 1800) ?: 1800,
                gracefulCloseMarginSeconds = env("CLOUD_BROWSER_GRACEFUL_CLOSE_MARGIN_SECONDS").toIntOrNull()?.coerceIn(30, 300) ?: 120,
                keepaliveIntervalSeconds = env("CLOUD_BROWSER_KEEPALIVE_INTERVAL_SECONDS").toIntOrNull()?.coerceIn(10, 60) ?: 30,
                heartbeatIntervalSeconds = env("CLOUD_BROWSER_HEARTBEAT_INTERVAL_SECONDS").toIntOrNull()?.coerceIn(5, 30) ?: 15,
                staleHeartbeatSeconds = env("CLOUD_BROWSER_STALE_HEARTBEAT_SECONDS").toIntOrNull()?.coerceIn(15, 120) ?: 45,
                reconciliationIntervalSeconds = env("CLOUD_BROWSER_RECONCILIATION_SECONDS").toIntOrNull()?.coerceIn(15, 300) ?: 30,
                globalActiveSessionLimit = env("CLOUD_BROWSER_GLOBAL_ACTIVE_LIMIT").toIntOrNull()?.coerceAtLeast(1) ?: 20,
                perUserActiveSessionLimit = env("CLOUD_BROWSER_PER_USER_ACTIVE_LIMIT").toIntOrNull()?.coerceAtLeast(1) ?: 2,
                retryAfterMinSeconds = env("CLOUD_BROWSER_RETRY_AFTER_MIN_SECONDS").toIntOrNull()?.coerceIn(5, 300) ?: 30,
                retryAfterMaxSeconds = env("CLOUD_BROWSER_RETRY_AFTER_MAX_SECONDS").toIntOrNull()?.coerceIn(10, 600) ?: 90,
                deleteConfirmTimeoutSeconds = env("CLOUD_BROWSER_DELETE_CONFIRM_TIMEOUT_SECONDS").toIntOrNull()?.coerceIn(5, 120) ?: 30,
                deleteConfirmPollSeconds = env("CLOUD_BROWSER_DELETE_CONFIRM_POLL_SECONDS").toIntOrNull()?.coerceIn(1, 10) ?: 2
            ).also {
                application.log.info(
                    "Cloud browser config enabled=${it.enabled} base=${it.apiBaseUrl} publicBase=${it.publicBaseUrl}"
                )
            }
        }
    }
}
