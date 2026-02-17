package com.suprbeta.core

import io.ktor.server.application.*
import kotlinx.coroutines.delay
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class SshCommandExecutorImpl(
    application: Application
) : SshCommandExecutor {
    private val logger = application.log

    companion object {
        private const val SSH_USER = "openclaw"
        private const val SSH_PORT = 22
        private const val SSH_PROBE_TIMEOUT_MS = 120_000L
        private const val SSH_AUTH_TIMEOUT_MS = 180_000L
        private const val SSH_CONNECT_TIMEOUT_MS = 10_000
        private const val SSH_MAX_RETRIES = 3
        private const val SSH_COMMAND_TIMEOUT_SECONDS = 300L
    }

    override suspend fun waitForSshReady(ipAddress: String) {
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

    override suspend fun waitForSshAuth(ipAddress: String, password: String) {
        val deadline = System.currentTimeMillis() + SSH_AUTH_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                val ssh = SSHClient()
                try {
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
                    ssh.connect(ipAddress, SSH_PORT)
                    ssh.authPassword(SSH_USER, password)
                    logger.info("SSH auth succeeded - cloud-init has applied password config")
                    return
                } finally {
                    runCatching { ssh.disconnect() }
                }
            } catch (e: Exception) {
                logger.info("SSH auth not ready yet (cloud-init still running): ${e.message}")
                delay(5000)
            }
        }

        throw IllegalStateException("SSH auth not available at $ipAddress after ${SSH_AUTH_TIMEOUT_MS / 1000}s - cloud-init may have failed")
    }

    override fun runSshCommand(ipAddress: String, password: String, command: String): String {
        var lastException: Exception? = null

        repeat(SSH_MAX_RETRIES) { attempt ->
            try {
                return runSshCommandOnce(ipAddress, password, command)
            } catch (e: Exception) {
                lastException = e
                if (attempt < SSH_MAX_RETRIES - 1) {
                    val delayMs = 2000L * (attempt + 1)
                    logger.warn("SSH command attempt ${attempt + 1} failed, retrying in ${delayMs}ms: ${e.message}")
                    Thread.sleep(delayMs)
                }
            }
        }

        throw lastException ?: RuntimeException("SSH command failed after $SSH_MAX_RETRIES attempts")
    }

    override fun runSshCommandOnce(ipAddress: String, password: String, command: String): String {
        val ssh = SSHClient()
        try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
            ssh.connect(ipAddress, SSH_PORT)
            ssh.authPassword(SSH_USER, password)

            val session = ssh.startSession()
            try {
                // Login shell keeps environment/session behavior consistent for OpenClaw commands.
                val cmd = session.exec("bash -l -c '${command.replace("'", "'\\''")}'")
                cmd.join(SSH_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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
                runCatching { session.close() }
            }
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
