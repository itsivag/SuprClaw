package com.suprbeta.runtime

import com.suprbeta.digitalocean.models.UserDropletInternal
import io.ktor.server.application.Application

class RuntimeWakeDispatcher(
    private val registry: AgentRuntimeRegistry,
    private val commandExecutor: RuntimeCommandExecutor,
    application: Application
) {
    suspend fun dispatch(
        droplet: UserDropletInternal,
        agentId: String,
        sessionKey: String,
        message: String
    ) {
        val adapter = registry.resolve(droplet)
        val command = adapter.buildWakeCommand(agentId, sessionKey, message)
            ?: throw IllegalStateException("Runtime ${adapter.runtime.wireValue} does not support wake dispatch")
        commandExecutor.run(droplet, command)
    }
}
