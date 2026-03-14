package com.suprbeta.runtime

import com.suprbeta.core.ShellEscaping.singleQuote
import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal

class RuntimeCommandExecutor(
    private val sshCommandExecutor: SshCommandExecutor
) {
    fun run(droplet: UserDropletInternal, command: String, singleAttempt: Boolean = false): String {
        val wrapped = wrapForDroplet(droplet, command)
        return if (singleAttempt) {
            sshCommandExecutor.runSshCommandOnce(droplet.ipAddress, wrapped)
        } else {
            sshCommandExecutor.runSshCommand(droplet.ipAddress, wrapped)
        }
    }

    fun wrapForDroplet(droplet: UserDropletInternal, command: String): String {
        val userShell = "su - ${RuntimePaths.runtimeUser} -s /bin/sh -lc ${singleQuote(command)}"
        return if (droplet.isDockerDeployment()) {
            val containerId = droplet.containerIdOrNull()
                ?: throw IllegalStateException("Missing container ID for docker deployment")
            "docker exec ${singleQuote(containerId)} $userShell"
        } else {
            command
        }
    }
}
