package com.suprbeta.docker

import com.suprbeta.core.SshCommandExecutor
import io.ktor.server.application.*

/**
 * Manages Traefik reverse proxy configuration for user containers.
 * 
 * Traefik uses file-based dynamic configuration. This service writes
 * YAML files to /opt/traefik/dynamic/ for each user container.
 */
class TraefikManager(
    private val sshCommandExecutor: SshCommandExecutor,
    private val application: Application
) {
    private val logger = application.log
    
    companion object {
        private const val TRAEFIK_DYNAMIC_DIR = "/opt/traefik/dynamic"
        private const val TRAEFIK_CONTAINER_NAME = "traefik"
    }
    
    /**
     * Adds a new route for a user container.
     * 
     * @param hostIp The host VPS IP address
     * @param subdomain The subdomain (e.g., user1.suprclaw.com)
     * @param port The host port mapped to the container
     */
    suspend fun addRoute(hostIp: String, subdomain: String, port: Int) {
        logger.info("Adding Traefik route: $subdomain -> localhost:$port")
        
        val configContent = generateRouterConfig(subdomain, port)
        val sanitizedName = sanitizeFilename(subdomain)
        val configPath = "$TRAEFIK_DYNAMIC_DIR/$sanitizedName.yml"
        
        // Write config file via SSH
        writeConfigFile(hostIp, configPath, configContent)
        
        // Trigger Traefik reload (it watches the directory, but we can force it)
        reloadConfig(hostIp)
        
        logger.info("Traefik route added: $subdomain")
    }
    
    /**
     * Removes a route for a user container.
     * 
     * @param hostIp The host VPS IP address
     * @param subdomain The subdomain to remove
     */
    suspend fun removeRoute(hostIp: String, subdomain: String) {
        logger.info("Removing Traefik route: $subdomain")
        
        val sanitizedName = sanitizeFilename(subdomain)
        val configPath = "$TRAEFIK_DYNAMIC_DIR/$sanitizedName.yml"
        
        // Remove config file
        sshCommandExecutor.runSshCommand(hostIp, "rm -f $configPath")
        
        // Reload config
        reloadConfig(hostIp)
        
        logger.info("Traefik route removed: $subdomain")
    }
    
    /**
     * Reloads Traefik configuration.
     * Traefik watches the directory by default, but this forces an immediate reload.
     */
    suspend fun reloadConfig(hostIp: String) {
        try {
            // Method 1: Send SIGHUP to Traefik container
            sshCommandExecutor.runSshCommand(
                hostIp,
                "docker kill --signal=HUP $TRAEFIK_CONTAINER_NAME 2>/dev/null || true"
            )
        } catch (e: Exception) {
            logger.warn("Failed to reload Traefik via signal: ${e.message}")
            // Traefik will auto-reload on file changes anyway
        }
    }
    
    /**
     * Lists all configured routes on a host.
     */
    suspend fun listRoutes(hostIp: String): List<String> {
        return try {
            val output = sshCommandExecutor.runSshCommand(
                hostIp,
                "ls -1 $TRAEFIK_DYNAMIC_DIR/*.yml 2>/dev/null | xargs -n1 basename -s .yml || echo ''"
            )
            output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            logger.error("Failed to list routes: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Checks if Traefik is running on the host.
     */
    suspend fun isTraefikRunning(hostIp: String): Boolean {
        return try {
            val result = sshCommandExecutor.runSshCommand(
                hostIp,
                "docker ps --filter 'name=$TRAEFIK_CONTAINER_NAME' --format '{{.Names}}' 2>/dev/null || echo ''"
            )
            result.contains(TRAEFIK_CONTAINER_NAME)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Gets Traefik health status.
     */
    suspend fun getTraefikHealth(hostIp: String): String {
        return try {
            sshCommandExecutor.runSshCommand(
                hostIp,
                "curl -s http://localhost:8080/ping 2>/dev/null || echo 'unhealthy'"
            ).trim()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
    
    /**
     * Starts Traefik if not running.
     */
    suspend fun ensureTraefikRunning(hostIp: String) {
        if (!isTraefikRunning(hostIp)) {
            logger.info("Starting Traefik on $hostIp")
            startTraefik(hostIp)
        }
    }
    
    /**
     * Generates the YAML configuration for a router.
     */
    private fun generateRouterConfig(subdomain: String, port: Int): String {
        val routerName = sanitizeRouterName(subdomain)
        val serviceName = "$routerName-service"
        
        return """
            http:
              routers:
                $routerName:
                  rule: "Host(`${subdomain}`)"
                  service: $serviceName
                  entryPoints:
                    - websecure
                  tls: {}
              
              services:
                $serviceName:
                  loadBalancer:
                    servers:
                      - url: "http://127.0.0.1:$port"
                    healthCheck:
                      path: /health
                      interval: 10s
                      timeout: 5s
        """.trimIndent()
    }
    
    /**
     * Writes a configuration file to the host.
     */
    private suspend fun writeConfigFile(hostIp: String, path: String, content: String) {
        // Create directory if needed
        sshCommandExecutor.runSshCommand(hostIp, "mkdir -p $TRAEFIK_DYNAMIC_DIR")
        
        // Write file using base64 to avoid escaping issues
        val base64Content = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
        sshCommandExecutor.runSshCommand(
            hostIp,
            "echo '$base64Content' | base64 -d > $path"
        )
        
        // Set permissions
        sshCommandExecutor.runSshCommand(hostIp, "chmod 644 $path")
    }
    
    /**
     * Starts Traefik container on the host.
     */
    private suspend fun startTraefik(hostIp: String) {
        val traefikVersion = "v3.0"
        
        val command = """
            docker run -d \
                --name $TRAEFIK_CONTAINER_NAME \
                --restart unless-stopped \
                --network host \
                -p 80:80 \
                -p 443:443 \
                -p 8080:8080 \
                -v /var/run/docker.sock:/var/run/docker.sock:ro \
                -v /opt/traefik/traefik.yml:/etc/traefik/traefik.yml:ro \
                -v /opt/traefik/dynamic:/opt/traefik/dynamic:ro \
                -v /opt/traefik/certs:/opt/traefik/certs:ro \
                -e TZ=UTC \
                traefik:$traefikVersion
        """.trimIndent().replace("\n", " ").trim()
        
        sshCommandExecutor.runSshCommand(hostIp, command)
        
        // Wait for Traefik to be ready
        var attempts = 0
        while (attempts < 30) {
            if (getTraefikHealth(hostIp) == "OK") {
                logger.info("Traefik is ready")
                return
            }
            kotlinx.coroutines.delay(1000)
            attempts++
        }
        
        throw IllegalStateException("Traefik failed to start within 30 seconds")
    }
    
    /**
     * Sanitizes a subdomain for use as a filename.
     */
    private fun sanitizeFilename(subdomain: String): String {
        return subdomain.replace(Regex("[^a-zA-Z0-9.-]"), "_")
    }
    
    /**
     * Sanitizes a subdomain for use as a router name in Traefik.
     */
    private fun sanitizeRouterName(subdomain: String): String {
        return subdomain.replace(Regex("[^a-zA-Z0-9]"), "-")
    }
}
