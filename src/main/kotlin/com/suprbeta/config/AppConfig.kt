package com.suprbeta.config

import com.suprbeta.runtime.AgentRuntime
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
    val dockerPicoclawImage: String =
        dotenv["DOCKER_PICOCLAW_IMAGE"] ?: System.getenv("DOCKER_PICOCLAW_IMAGE") ?: "ghcr.io/itsivag/picoclaw:latest"
    val dockerPicoclawBuildContext: String =
        dotenv["DOCKER_PICOCLAW_BUILD_CONTEXT"]
            ?: System.getenv("DOCKER_PICOCLAW_BUILD_CONTEXT")
            ?: "https://github.com/itsivag/suprclaw.git#main"
    val dockerPicoclawBuildDockerfile: String =
        dotenv["DOCKER_PICOCLAW_BUILD_DOCKERFILE"]
            ?: System.getenv("DOCKER_PICOCLAW_BUILD_DOCKERFILE")
            ?: "docker/picoclaw-container/Dockerfile"
    val defaultAgentRuntime: AgentRuntime = AgentRuntime.PICOCLAW
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
    val dockerDnsPropagationTimeoutSeconds: Long =
        (dotenv["DOCKER_DNS_PROPAGATION_TIMEOUT_SECONDS"]
            ?: System.getenv("DOCKER_DNS_PROPAGATION_TIMEOUT_SECONDS")
            ?: "20").toLongOrNull() ?: 20L
    val dockerPortMin: Int =
        (dotenv["DOCKER_PORT_MIN"] ?: System.getenv("DOCKER_PORT_MIN") ?: "18001").toIntOrNull() ?: 18001
    val dockerPortMax: Int =
        (dotenv["DOCKER_PORT_MAX"] ?: System.getenv("DOCKER_PORT_MAX") ?: "18050").toIntOrNull() ?: 18050

    fun initialize(application: Application) {
        application.log.info(
            "Deployment mode: Docker (runtime=${defaultAgentRuntime.wireValue}, image=$dockerPicoclawImage)"
        )
    }
}
