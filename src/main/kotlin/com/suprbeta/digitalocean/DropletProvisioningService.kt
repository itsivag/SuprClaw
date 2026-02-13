package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.ProvisioningStatus
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Orchestrates OpenClaw droplet provisioning using Pattern A (SSH Onboarding).
 *
 * After creating a droplet with minimal cloud-init (user creation only),
 * this service polls until the droplet is active, SSHs in, and runs
 * openclaw onboarding remotely — so systemd user daemon works correctly.
 */
class DropletProvisioningService(
    private val digitalOceanService: DigitalOceanService,
    private val application: Application
) {
    private val logger = application.log

    /** In-memory status map keyed by droplet ID. */
    val statuses = ConcurrentHashMap<Long, ProvisioningStatus>()

    companion object {
        private const val SSH_USER = "openclaw"
        private const val SSH_PORT = 22
        private const val GATEWAY_PORT = 18789
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WAIT_MS = 300_000L       // 5 minutes for droplet to become active
        private const val SSH_PROBE_TIMEOUT_MS = 120_000L   // 2 minutes for SSH port
        private const val SSH_AUTH_TIMEOUT_MS = 180_000L    // 3 minutes for cloud-init to apply password
        private const val SSH_CONNECT_TIMEOUT_MS = 10_000    // 10s per SSH attempt
        private const val ONBOARDING_MAX_RETRIES = 3
        private const val GATEWAY_VERIFY_TIMEOUT_S = 30
    }

    // ── Phase 1 — Droplet Creation ──────────────────────────────────────

    /**
     * Creates the droplet and returns info needed to kick off provisioning.
     * The returned password is held in memory only until SSH completes.
     */
    data class CreateResult(val dropletId: Long, val status: ProvisioningStatus, val password: String, val geminiApiKey: String)

    suspend fun createAndProvision(name: String): CreateResult {
        val password = UserDataGenerator.generatePassword()
        val geminiApiKey = digitalOceanService.geminiApiKey

        val doResponse = digitalOceanService.createDroplet(name, password)
        val dropletId = doResponse.droplet?.id
            ?: throw IllegalStateException("DigitalOcean did not return a droplet ID")

        val status = ProvisioningStatus(
            droplet_id = dropletId,
            droplet_name = name,
            phase = ProvisioningStatus.PHASE_CREATING,
            message = "Droplet created, waiting for it to become active...",
            started_at = Instant.now().toString()
        )
        statuses[dropletId] = status

        return CreateResult(dropletId, status, password, geminiApiKey)
    }

    /**
     * Internal entry point that drives the provisioning coroutine.
     * Called as fire-and-forget from the route handler.
     */
    suspend fun provisionDroplet(dropletId: Long, password: String, geminiApiKey: String) {
        try {
            // Phase 2 — Wait for active + IP
            val ipAddress = waitForDropletReady(dropletId)

            // Phase 3 — Wait for SSH port
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for SSH port at $ipAddress...")
            waitForSshReady(ipAddress)

            // Phase 3b — Wait for cloud-init to apply password (deterministic probe)
            updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_SSH, "Waiting for cloud-init to finish (probing SSH auth)...", ipAddress)
            waitForSshAuth(ipAddress, password)

            // Phase 4 — Onboarding (with retries)
            updateStatus(dropletId, ProvisioningStatus.PHASE_ONBOARDING, "Running OpenClaw onboarding via SSH...", ipAddress)
            runOnboardingWithRetries(ipAddress, password, geminiApiKey)

            // Phase 5 — Model configuration
            updateStatus(dropletId, ProvisioningStatus.PHASE_CONFIGURING, "Setting AI model...", ipAddress)
            runSshCommand(ipAddress, password, "openclaw models set google/gemini-2.5-flash")

            // Phase 6 — Gateway verification
            updateStatus(dropletId, ProvisioningStatus.PHASE_VERIFYING, "Verifying gateway status...", ipAddress)
            verifyGateway(ipAddress, password)

            // Phase 7 — Nginx reverse proxy
            updateStatus(dropletId, ProvisioningStatus.PHASE_NGINX, "Installing and configuring nginx reverse proxy...", ipAddress)
            setupNginxReverseProxy(ipAddress, password)

            // Phase 8 — Complete
            updateStatus(
                dropletId, ProvisioningStatus.PHASE_COMPLETE,
                "Provisioning complete. Gateway exposed at http://$ipAddress (port 80 → $GATEWAY_PORT).",
                ipAddress,
                completedAt = Instant.now().toString()
            )
            logger.info("✅ Droplet $dropletId provisioning complete at http://$ipAddress")

        } catch (e: Exception) {
            logger.error("❌ Droplet $dropletId provisioning failed, deleting droplet...", e)

            // Clean up: destroy the failed droplet
            try {
                digitalOceanService.deleteDroplet(dropletId)
                logger.info("🗑️ Droplet $dropletId deleted after provisioning failure")
            } catch (deleteError: Exception) {
                logger.error("Failed to delete droplet $dropletId: ${deleteError.message}")
            }

            val current = statuses[dropletId]
            statuses[dropletId] = current?.copy(
                phase = ProvisioningStatus.PHASE_FAILED,
                message = "Provisioning failed (droplet destroyed): ${e.message}",
                error = e.stackTraceToString(),
                completed_at = Instant.now().toString()
            ) ?: ProvisioningStatus(
                droplet_id = dropletId,
                droplet_name = "unknown",
                phase = ProvisioningStatus.PHASE_FAILED,
                message = "Provisioning failed (droplet destroyed): ${e.message}",
                error = e.stackTraceToString(),
                started_at = Instant.now().toString(),
                completed_at = Instant.now().toString()
            )
        }
    }

    // ── Phase 2 — Poll until active ─────────────────────────────────────

    private suspend fun waitForDropletReady(dropletId: Long): String {
        updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_ACTIVE, "Polling DigitalOcean API for droplet status...")

        val deadline = System.currentTimeMillis() + MAX_POLL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val response = digitalOceanService.getDroplet(dropletId)
                val droplet = response.droplet

                if (droplet?.status == "active") {
                    val ip = droplet.networks?.v4
                        ?.firstOrNull { it.type == "public" }
                        ?.ip_address

                    if (ip != null) {
                        logger.info("Droplet $dropletId is active at $ip")
                        updateStatus(dropletId, ProvisioningStatus.PHASE_WAITING_ACTIVE, "Droplet active at $ip", ip)
                        return ip
                    }
                }

                logger.info("Droplet $dropletId status: ${droplet?.status ?: "unknown"}, waiting...")
            } catch (e: Exception) {
                logger.warn("Error polling droplet $dropletId: ${e.message}")
            }

            delay(POLL_INTERVAL_MS)
        }

        throw IllegalStateException("Droplet $dropletId did not become active within ${MAX_POLL_WAIT_MS / 1000}s")
    }

    // ── Phase 3 — SSH port probe ────────────────────────────────────────

    private suspend fun waitForSshReady(ipAddress: String) {
        val deadline = System.currentTimeMillis() + SSH_PROBE_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ipAddress, SSH_PORT), 3000)
                }
                logger.info("SSH port reachable at $ipAddress")
                return
            } catch (_: IOException) {
                delay(2000)
            }
        }

        throw IllegalStateException("SSH not reachable at $ipAddress after ${SSH_PROBE_TIMEOUT_MS / 1000}s")
    }

    /**
     * Deterministically waits for cloud-init to finish applying password config.
     * Instead of guessing with a fixed delay, we actively probe SSH auth
     * every 5 seconds until it succeeds — meaning cloud-init has applied
     * ssh_pwauth + chpasswd.
     */
    private suspend fun waitForSshAuth(ipAddress: String, password: String) {
        val deadline = System.currentTimeMillis() + SSH_AUTH_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                val ssh = SSHClient()
                try {
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
                    ssh.connect(ipAddress, SSH_PORT)
                    ssh.authPassword(SSH_USER, password)
                    logger.info("SSH auth succeeded — cloud-init has applied password config")
                    return
                } finally {
                    ssh.disconnect()
                }
            } catch (e: Exception) {
                logger.info("SSH auth not ready yet (cloud-init still running): ${e.message}")
                delay(5000)
            }
        }

        throw IllegalStateException("SSH auth not available at $ipAddress after ${SSH_AUTH_TIMEOUT_MS / 1000}s — cloud-init may have failed")
    }

    // ── Phase 4 — Onboarding via SSH ────────────────────────────────────

    private suspend fun runOnboardingWithRetries(ipAddress: String, password: String, geminiApiKey: String) {
        val command = """
            openclaw onboard \
              --install-daemon \
              --non-interactive \
              --auth-choice gemini-api-key \
              --gemini-api-key "$geminiApiKey" \
              --gateway-port $GATEWAY_PORT \
              --accept-risk
        """.trimIndent()

        var lastError: Exception? = null

        repeat(ONBOARDING_MAX_RETRIES) { attempt ->
            try {
                logger.info("Onboarding attempt ${attempt + 1}/$ONBOARDING_MAX_RETRIES on $ipAddress")
                val output = runSshCommand(ipAddress, password, command)

                if (output.contains("Installed systemd service") || output.contains("Enabled systemd lingering") || output.contains("onboarding complete", ignoreCase = true)) {
                    logger.info("Onboarding succeeded on attempt ${attempt + 1}")
                    return
                }

                // Even if we don't see expected markers, if command didn't throw, consider it success
                logger.info("Onboarding completed (attempt ${attempt + 1}), output: ${output.take(500)}")
                return

            } catch (e: Exception) {
                lastError = e
                logger.warn("Onboarding attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < ONBOARDING_MAX_RETRIES - 1) {
                    delay(5000)
                }
            }
        }

        throw IllegalStateException(
            "Onboarding failed after $ONBOARDING_MAX_RETRIES attempts: ${lastError?.message}", lastError
        )
    }

    // ── Phase 6 — Gateway verification ──────────────────────────────────

    private suspend fun verifyGateway(ipAddress: String, password: String) {
        val deadline = System.currentTimeMillis() + (GATEWAY_VERIFY_TIMEOUT_S * 1000L)

        while (System.currentTimeMillis() < deadline) {
            try {
                val output = runSshCommand(ipAddress, password, "openclaw gateway status")
                logger.info("Gateway status: ${output.take(200)}")
                return  // exit 0 means success
            } catch (e: Exception) {
                logger.info("Gateway not ready yet: ${e.message}")
                delay(2000)
            }
        }

        throw IllegalStateException("Gateway did not become ready within ${GATEWAY_VERIFY_TIMEOUT_S}s")
    }

    // ── Phase 7 — Nginx reverse proxy ───────────────────────────────────

    private fun setupNginxReverseProxy(ipAddress: String, password: String) {
        logger.info("Installing nginx on $ipAddress...")

        // Install nginx
        runSshCommand(ipAddress, password, "sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx")

        // Build the nginx config as a plain string, then base64 encode it
        // to avoid all shell escaping issues (heredocs don't work inside bash -l -c '...')
        val nginxConfig = """
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:$GATEWAY_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade ${'$'}http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host ${'$'}host;
        proxy_set_header X-Real-IP ${'$'}remote_addr;
        proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto ${'$'}scheme;
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
}
""".trimIndent()

        val encoded = java.util.Base64.getEncoder().encodeToString(nginxConfig.toByteArray())
        runSshCommand(ipAddress, password, "echo $encoded | base64 -d | sudo tee /etc/nginx/sites-available/openclaw > /dev/null")

        // Enable site and disable default
        runSshCommand(ipAddress, password, "sudo ln -sf /etc/nginx/sites-available/openclaw /etc/nginx/sites-enabled/openclaw")
        runSshCommand(ipAddress, password, "sudo rm -f /etc/nginx/sites-enabled/default")

        // Test and reload
        runSshCommand(ipAddress, password, "sudo nginx -t")
        runSshCommand(ipAddress, password, "sudo systemctl reload nginx")

        logger.info("Nginx reverse proxy configured: port 80 -> $GATEWAY_PORT")

        // Configure UFW firewall — allow SSH first (critical!), then HTTP, then enable
        runSshCommand(ipAddress, password, "sudo ufw allow OpenSSH")
        runSshCommand(ipAddress, password, "sudo ufw allow 'Nginx Full'")
        runSshCommand(ipAddress, password, "sudo ufw --force enable")

        logger.info("UFW firewall enabled: SSH + Nginx allowed")
    }

    // ── SSH helper ──────────────────────────────────────────────────────

    /**
     * Runs a command over SSH as the openclaw user with password auth.
     * Uses a login shell (bash -l) so PAM session + systemd user bus are available.
     */
    private fun runSshCommand(ipAddress: String, password: String, command: String): String {
        val ssh = SSHClient()
        try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
            ssh.connect(ipAddress, SSH_PORT)
            ssh.authPassword(SSH_USER, password)

            val session = ssh.startSession()
            try {
                // Wrap in bash -l to get a real login session (PAM + systemd user bus)
                val cmd = session.exec("bash -l -c '${command.replace("'", "'\\''")}'")
                cmd.join(120, TimeUnit.SECONDS)

                val stdout = cmd.inputStream.bufferedReader().readText()
                val stderr = cmd.errorStream.bufferedReader().readText()
                val exitStatus = cmd.exitStatus

                logger.info("SSH command exit=$exitStatus stdout=${stdout.take(200)}")
                if (stderr.isNotBlank()) {
                    logger.info("SSH stderr: ${stderr.take(200)}")
                }

                if (exitStatus != null && exitStatus != 0) {
                    throw RuntimeException("SSH command failed (exit=$exitStatus): ${stderr.take(500)}")
                }

                return stdout
            } finally {
                session.close()
            }
        } finally {
            ssh.disconnect()
        }
    }

    // ── Status helpers ──────────────────────────────────────────────────

    private fun updateStatus(
        dropletId: Long,
        phase: String,
        message: String,
        ipAddress: String? = null,
        completedAt: String? = null
    ) {
        val current = statuses[dropletId] ?: return
        statuses[dropletId] = current.copy(
            phase = phase,
            message = message,
            ip_address = ipAddress ?: current.ip_address,
            completed_at = completedAt
        )
    }

}
