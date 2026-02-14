package com.suprbeta.config

import io.ktor.server.application.*

/**
 * Application deployment configuration
 */
object AppConfig {
    private var _environment: DeploymentEnvironment = DeploymentEnvironment.LOCAL
    private var _sslEnabled: Boolean = false

    val environment: DeploymentEnvironment
        get() = _environment

    val sslEnabled: Boolean
        get() = _sslEnabled

    val isLocal: Boolean
        get() = _environment == DeploymentEnvironment.LOCAL

    val isVps: Boolean
        get() = _environment == DeploymentEnvironment.VPS

    /**
     * Initialize configuration from Ktor application environment
     */
    fun initialize(application: Application) {
        val envString = application.environment.config.propertyOrNull("deployment.environment")?.getString() ?: "local"
        _environment = when (envString.lowercase()) {
            "vps", "production", "prod" -> DeploymentEnvironment.VPS
            else -> DeploymentEnvironment.LOCAL
        }

        _sslEnabled = application.environment.config.propertyOrNull("deployment.ssl.enabled")?.getString()?.toBoolean() ?: false

        application.log.info("📝 Deployment environment: $_environment (SSL: $_sslEnabled)")
    }
}

enum class DeploymentEnvironment {
    LOCAL,
    VPS
}
