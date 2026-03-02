package com.suprbeta.core

interface SshCommandExecutor {
    suspend fun waitForSshReady(ipAddress: String)

    suspend fun waitForSshAuth(ipAddress: String)

    fun runSshCommand(ipAddress: String, command: String): String

    fun runSshCommandOnce(ipAddress: String, command: String): String
}
