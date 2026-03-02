package com.suprbeta.config

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*

/**
 * Application deployment configuration.
 * Set SSL_ENABLED=false in .env for local development.
 */
object AppConfig {
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    val sslEnabled: Boolean =
        (dotenv["SSL_ENABLED"] ?: System.getenv("SSL_ENABLED") ?: "true").equals("true", ignoreCase = true)

    fun initialize(application: Application) {
        application.log.info("📝 Deployment mode: VPS (SSL: $sslEnabled)")
    }
}
