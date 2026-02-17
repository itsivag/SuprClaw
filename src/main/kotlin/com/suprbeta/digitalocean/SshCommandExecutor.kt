package com.suprbeta.digitalocean

interface SshCommandExecutor {
    suspend fun waitForSshReady(ipAddress: String)

    suspend fun waitForSshAuth(ipAddress: String, password: String)

    fun runSshCommand(ipAddress: String, password: String, command: String): String

    fun runSshCommandOnce(ipAddress: String, password: String, command: String): String
}
