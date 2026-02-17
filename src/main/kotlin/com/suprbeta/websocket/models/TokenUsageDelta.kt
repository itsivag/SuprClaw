package com.suprbeta.websocket.models

/**
 * Aggregated token usage counters to be persisted in Firestore.
 */
data class TokenUsageDelta(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val inboundPromptTokens: Long = 0,
    val inboundCompletionTokens: Long = 0,
    val inboundTotalTokens: Long = 0,
    val outboundPromptTokens: Long = 0,
    val outboundCompletionTokens: Long = 0,
    val outboundTotalTokens: Long = 0,
    val usageEvents: Long = 0
) {
    fun plus(other: TokenUsageDelta): TokenUsageDelta = TokenUsageDelta(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        inboundPromptTokens = inboundPromptTokens + other.inboundPromptTokens,
        inboundCompletionTokens = inboundCompletionTokens + other.inboundCompletionTokens,
        inboundTotalTokens = inboundTotalTokens + other.inboundTotalTokens,
        outboundPromptTokens = outboundPromptTokens + other.outboundPromptTokens,
        outboundCompletionTokens = outboundCompletionTokens + other.outboundCompletionTokens,
        outboundTotalTokens = outboundTotalTokens + other.outboundTotalTokens,
        usageEvents = usageEvents + other.usageEvents
    )

    fun isZero(): Boolean {
        return promptTokens == 0L &&
            completionTokens == 0L &&
            totalTokens == 0L &&
            inboundPromptTokens == 0L &&
            inboundCompletionTokens == 0L &&
            inboundTotalTokens == 0L &&
            outboundPromptTokens == 0L &&
            outboundCompletionTokens == 0L &&
            outboundTotalTokens == 0L &&
            usageEvents == 0L
    }

    companion object {
        fun inbound(tokens: Long): TokenUsageDelta = TokenUsageDelta(
            promptTokens = tokens,
            totalTokens = tokens,
            inboundPromptTokens = tokens,
            inboundTotalTokens = tokens,
            usageEvents = 1
        )

        fun outbound(tokens: Long): TokenUsageDelta = TokenUsageDelta(
            completionTokens = tokens,
            totalTokens = tokens,
            outboundCompletionTokens = tokens,
            outboundTotalTokens = tokens,
            usageEvents = 1
        )
    }
}
