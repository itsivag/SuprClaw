package com.suprbeta.runtime

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.websocket.models.WebSocketFrame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PicoClawChatBridge(
    private val registry: AgentRuntimeRegistry,
    private val commandExecutor: RuntimeCommandExecutor
) {
    private val ansiRegex = Regex("""\u001B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")
    private val promptRegex = Regex("""(?m)^🦞\s*$""")

    fun runChat(droplet: UserDropletInternal, sessionKey: String, message: String, requestId: String?): WebSocketFrame {
        val adapter = registry.resolve(droplet)
        require(adapter.runtime == AgentRuntime.PICOCLAW) { "PicoClaw bridge can only be used for picoclaw droplets" }

        val command = adapter.buildDirectChatCommand(sessionKey = sessionKey, message = message)
            ?: throw IllegalStateException("PicoClaw direct chat command is unavailable")
        val response = normalizeOutput(commandExecutor.run(droplet, command))

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

    internal fun normalizeOutput(raw: String): String {
        val normalized = ansiRegex.replace(raw, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        promptRegex.findAll(normalized).lastOrNull()?.let { match ->
            val afterPrompt = normalized.substring(match.range.last + 1).trim()
            if (afterPrompt.isNotBlank()) return afterPrompt
        }

        return normalized
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() }
            ?: normalized
    }
}
