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

    // Shared-host container provisioning settings
    val podmanPicoclawImage: String =
        dotenv["PODMAN_PICOCLAW_IMAGE"]
            ?: System.getenv("PODMAN_PICOCLAW_IMAGE")
            ?: "ghcr.io/itsivag/picoclaw:latest"
    val podmanPicoclawBuildContext: String =
        dotenv["PODMAN_PICOCLAW_BUILD_CONTEXT"]
            ?: System.getenv("PODMAN_PICOCLAW_BUILD_CONTEXT")
            ?: "https://github.com/itsivag/suprclaw.git#main"
    val podmanPicoclawContainerfile: String =
        dotenv["PODMAN_PICOCLAW_CONTAINERFILE"]
            ?: System.getenv("PODMAN_PICOCLAW_CONTAINERFILE")
            ?: "containers/picoclaw-container/Containerfile"
    val defaultAgentRuntime: AgentRuntime = AgentRuntime.PICOCLAW
    val podmanHostCapacity: Int =
        (dotenv["PODMAN_HOST_CAPACITY"]
            ?: System.getenv("PODMAN_HOST_CAPACITY")
            ?: "20").toIntOrNull() ?: 20
    val podmanContainerMemory: String =
        dotenv["PODMAN_CONTAINER_MEMORY"]
            ?: System.getenv("PODMAN_CONTAINER_MEMORY")
            ?: "2g"
    val podmanContainerCpu: String =
        dotenv["PODMAN_CONTAINER_CPU"]
            ?: System.getenv("PODMAN_CONTAINER_CPU")
            ?: "0.5"
    val podmanGatewayReadyTimeoutSeconds: Long =
        (dotenv["PODMAN_GATEWAY_READY_TIMEOUT_SECONDS"]
            ?: System.getenv("PODMAN_GATEWAY_READY_TIMEOUT_SECONDS")
            ?: "300").toLongOrNull() ?: 300L
    val podmanDnsPropagationTimeoutSeconds: Long =
        (dotenv["PODMAN_DNS_PROPAGATION_TIMEOUT_SECONDS"]
            ?: System.getenv("PODMAN_DNS_PROPAGATION_TIMEOUT_SECONDS")
            ?: "20").toLongOrNull() ?: 20L
    val podmanPortMin: Int =
        (dotenv["PODMAN_PORT_MIN"]
            ?: System.getenv("PODMAN_PORT_MIN")
            ?: "18001").toIntOrNull() ?: 18001
    val podmanPortMax: Int =
        (dotenv["PODMAN_PORT_MAX"]
            ?: System.getenv("PODMAN_PORT_MAX")
            ?: "18050").toIntOrNull() ?: 18050

    fun initialize(application: Application) {
        application.log.info(
            "Deployment mode: Podman (runtime=${defaultAgentRuntime.wireValue}, image=$podmanPicoclawImage)"
        )
    }
}
