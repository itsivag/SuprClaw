package com.suprbeta.runtime

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.websocket.models.WebSocketFrame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PicoClawChatBridge(
    private val registry: AgentRuntimeRegistry,
    private val commandExecutor: RuntimeCommandExecutor
) {
    fun runChat(droplet: UserDropletInternal, sessionKey: String, message: String, requestId: String?): WebSocketFrame {
        val adapter = registry.resolve(droplet)
        require(adapter.runtime == AgentRuntime.PICOCLAW) { "PicoClaw bridge can only be used for picoclaw droplets" }

        val command = adapter.buildDirectChatCommand(sessionKey = sessionKey, message = message)
            ?: throw IllegalStateException("PicoClaw direct chat command is unavailable")
        val response = commandExecutor.run(droplet, command).trim()

        return WebSocketFrame(
            type = "event",
            id = requestId,
            event = "chat",
            state = "final",
            payload = buildJsonObject {
                put("text", response)
                put("content", response)
                put("sessionKey", sessionKey)
            }
        )
    }
}
