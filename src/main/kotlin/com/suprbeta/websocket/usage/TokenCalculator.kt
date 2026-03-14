package com.suprbeta.websocket.usage

import com.suprbeta.websocket.models.TokenUsageDelta
import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.server.application.*
import kotlinx.serialization.json.*

/**
 * Extracts token usage metadata from runtime chat events.
 */
class TokenCalculator(
    application: Application,
    private val json: Json
) {
    companion object {
        const val DEFAULT_MODEL = "amazon/nova-pro"
    }

    private val logger = application.log

    /**
     * Inspects the frame for usage metadata.
     * Usage is primarily sent in the `chat` event payload.
     */
    fun extractTokenUsage(frame: WebSocketFrame, model: String): TokenUsageDelta {
        var inputTokens = 0L
        var outputTokens = 0L

        // 1. Check for 'chat' events (where Bedrock usage is typically attached)
        if (frame.event == "chat" && frame.payload is JsonObject) {
            val payload = frame.payload.jsonObject
            payload["usage"]?.jsonObject?.let { usage ->
                val (inT, outT) = findTokensInObject(usage)
                inputTokens += inT
                outputTokens += outT
            }
        }

        // 2. Fallback: Search anywhere in payload/result for robustness
        if (inputTokens == 0L && outputTokens == 0L) {
            val (inT, outT) = findTokensInObject(frame.payload)
            inputTokens += inT
            outputTokens += outT
            
            if (inputTokens == 0L && outputTokens == 0L) {
                val (inTRes, outTRes) = findTokensInObject(frame.result)
                inputTokens += inTRes
                outputTokens += outTRes
            }
        }

        if (inputTokens == 0L && outputTokens == 0L) {
            return TokenUsageDelta()
        }

        logger.debug("Perfect Usage match: In=$inputTokens, Out=$outputTokens")

        return TokenUsageDelta(
            promptTokens = inputTokens,
            completionTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            // Map prompt to inbound and completion to outbound relative to the model
            inboundPromptTokens = inputTokens,
            inboundTotalTokens = inputTokens,
            outboundCompletionTokens = outputTokens,
            outboundTotalTokens = outputTokens,
            usageEvents = 1
        )
    }

    /**
     * Recursively looks for Bedrock token keys in a JSON object.
     */
    private fun findTokensInObject(element: JsonElement?): Pair<Long, Long> {
        if (element == null || element !is JsonObject) return Pair(0L, 0L)
        
        var inT = 0L
        var outT = 0L

        // Direct keys in this object
        inT += element["inputTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        inT += element["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        inT += element["inputTokenCount"]?.jsonPrimitive?.longOrNull ?: 0L

        outT += element["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        outT += element["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        outT += element["outputTokenCount"]?.jsonPrimitive?.longOrNull ?: 0L

        // Check if there's a nested usage or metrics block
        element["usage"]?.jsonObject?.let { 
            val nested = findTokensInObject(it)
            inT += nested.first
            outT += nested.second
        }
        
        element["amazon-bedrock-invocationMetrics"]?.jsonObject?.let {
            val nested = findTokensInObject(it)
            inT += nested.first
            outT += nested.second
        }

        return Pair(inT, outT)
    }
}
