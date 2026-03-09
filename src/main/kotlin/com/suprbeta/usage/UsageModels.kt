package com.suprbeta.usage

import kotlinx.serialization.Serializable

/**
 * Model representing daily token usage data stored in Firestore
 * Credits = (inputTokens * 1 + outputTokens * 2) / 1000
 */
@Serializable
data class DailyUsageData(
    val userId: String = "",
    val dayUtc: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val inboundPromptTokens: Long = 0,
    val inboundCompletionTokens: Long = 0,
    val inboundTotalTokens: Long = 0,
    val outboundPromptTokens: Long = 0,
    val outboundCompletionTokens: Long = 0,
    val outboundTotalTokens: Long = 0,
    val usageEvents: Long = 0,
    val lastSessionId: String = "",
    val credits: Long = 0
)
