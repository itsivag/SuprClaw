package com.suprbeta.config

import io.ktor.server.application.*

/**
 * Application deployment configuration - always runs in VPS mode
 */
object AppConfig {
    // Always use VPS configuration with SSL enabled
    val sslEnabled: Boolean = true

    fun initialize(application: Application) {
        application.log.info("📝 Deployment mode: VPS (SSL: $sslEnabled)")
    }
}
