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

    // Docker container provisioning settings
    val dockerOpenclawImage: String =
        dotenv["DOCKER_OPENCLAW_IMAGE"] ?: System.getenv("DOCKER_OPENCLAW_IMAGE") ?: "suprclaw/openclaw:latest"
    val dockerHostCapacity: Int =
        (dotenv["DOCKER_HOST_CAPACITY"] ?: System.getenv("DOCKER_HOST_CAPACITY") ?: "20").toIntOrNull() ?: 20
    val dockerContainerMemory: String =
        dotenv["DOCKER_CONTAINER_MEMORY"] ?: System.getenv("DOCKER_CONTAINER_MEMORY") ?: "2g"
    val dockerContainerCpu: String =
        dotenv["DOCKER_CONTAINER_CPU"] ?: System.getenv("DOCKER_CONTAINER_CPU") ?: "0.5"
    val dockerGatewayReadyTimeoutSeconds: Long =
        (dotenv["DOCKER_GATEWAY_READY_TIMEOUT_SECONDS"]
            ?: System.getenv("DOCKER_GATEWAY_READY_TIMEOUT_SECONDS")
            ?: "300").toLongOrNull() ?: 300L
    val dockerPortMin: Int =
        (dotenv["DOCKER_PORT_MIN"] ?: System.getenv("DOCKER_PORT_MIN") ?: "18001").toIntOrNull() ?: 18001
    val dockerPortMax: Int =
        (dotenv["DOCKER_PORT_MAX"] ?: System.getenv("DOCKER_PORT_MAX") ?: "18050").toIntOrNull() ?: 18050

    fun initialize(application: Application) {
        application.log.info("Deployment mode: Docker (image: $dockerOpenclawImage)")
    }
}
