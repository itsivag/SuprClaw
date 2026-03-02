package com.suprbeta.core

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshCommandExecutorImpl(
    application: Application
) : SshCommandExecutor {
    private val logger = application.log

    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private val privateKeyPem: String by lazy {
        val b64 = dotenv["PROVISIONING_SSH_PRIVATE_KEY_B64"]
            ?: System.getenv("PROVISIONING_SSH_PRIVATE_KEY_B64")
            ?: throw IllegalStateException("PROVISIONING_SSH_PRIVATE_KEY_B64 not found in environment")
        String(Base64.getDecoder().decode(b64))
    }

    companion object {
        private const val SSH_USER = "openclaw"
        private const val SSH_PORT = 22
        private const val SSH_PROBE_TIMEOUT_MS = 120_000L
        private const val SSH_AUTH_TIMEOUT_MS = 180_000L
        private const val SSH_CONNECT_TIMEOUT_MS = 10_000
        private const val SSH_MAX_RETRIES = 3
        private const val SSH_COMMAND_TIMEOUT_SECONDS = 120L
    }

    private fun withKeyFile(block: (File) -> Unit) {
        val tempKey = File.createTempFile("suprclaw_key_", ".pem")
        try {
            tempKey.writeText(privateKeyPem)
            tempKey.setReadable(false, false)
            tempKey.setReadable(true, true)
            block(tempKey)
        } finally {
            tempKey.delete()
        }
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

    override suspend fun waitForSshAuth(ipAddress: String) {
        val deadline = System.currentTimeMillis() + SSH_AUTH_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                withKeyFile { keyFile ->
                    val ssh = SSHClient()
                    try {
                        ssh.addHostKeyVerifier(PromiscuousVerifier())
                        ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
                        ssh.connect(ipAddress, SSH_PORT)
                        ssh.authPublickey(SSH_USER, ssh.loadKeys(keyFile.absolutePath))
                        logger.info("SSH key auth succeeded at $ipAddress")
                    } finally {
                        runCatching { ssh.disconnect() }
                    }
                }
                return
            } catch (e: Exception) {
                logger.info("SSH auth not ready yet: ${e.message}")
                delay(5000)
            }
        }

        throw IllegalStateException("SSH auth not available at $ipAddress after ${SSH_AUTH_TIMEOUT_MS / 1000}s")
    }

    override fun runSshCommand(ipAddress: String, command: String): String {
        var lastException: Exception? = null

        repeat(SSH_MAX_RETRIES) { attempt ->
            try {
                return runSshCommandOnce(ipAddress, command)
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

    override fun runSshCommandOnce(ipAddress: String, command: String): String {
        var result = ""
        withKeyFile { keyFile ->
            val ssh = SSHClient()
            try {
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connectTimeout = SSH_CONNECT_TIMEOUT_MS
                ssh.connect(ipAddress, SSH_PORT)
                ssh.authPublickey(SSH_USER, ssh.loadKeys(keyFile.absolutePath))

                val session = ssh.startSession()
                try {
                    val cmd = session.exec("bash -l -c '${command.replace("'", "'\\''")}'")
                    cmd.join(SSH_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                    val stdout = cmd.inputStream.bufferedReader().readText()
                    val stderr = cmd.errorStream.bufferedReader().readText()
                    val exitStatus = cmd.exitStatus

                    logger.info("SSH command exit=$exitStatus stdout=${stdout.take(200)}")
                    if (stderr.isNotBlank()) {
                        logger.info("SSH stderr: ${stderr.take(200)}")
                    }

                    if (exitStatus == null) {
                        throw RuntimeException("SSH command timed out after ${SSH_COMMAND_TIMEOUT_SECONDS}s: ${command.take(100)}")
                    }

                    if (exitStatus != 0) {
                        throw RuntimeException("SSH command failed (exit=$exitStatus): ${stderr.take(500)}")
                    }

                    result = stdout
                } finally {
                    runCatching { session.close() }
                }
            } finally {
                runCatching { ssh.disconnect() }
            }
        }
        return result
    }
}
