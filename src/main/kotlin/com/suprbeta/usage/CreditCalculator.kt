package com.suprbeta.usage

import com.suprbeta.websocket.models.TokenUsageDelta

/**
 * Converts token usage to credits with weighted pricing:
 * - Input tokens: 1x weight (cheaper)
 * - Output tokens: 2x weight (more expensive - generation costs more)
 * - Conversion: 1000 weighted tokens = 1 credit
 *
 * Formula: credits = (inputTokens * 1 + outputTokens * 2) / 1000
 */
object CreditCalculator {
    
    private const val INPUT_WEIGHT = 1L
    private const val OUTPUT_WEIGHT = 2L
    private const val TOKENS_PER_CREDIT = 1000L
    
    /**
     * Converts token usage to credits with weighted pricing
     */
    fun toCredits(delta: TokenUsageDelta): Long {
        val weightedTokens = (delta.promptTokens * INPUT_WEIGHT) + (delta.completionTokens * OUTPUT_WEIGHT)
        return weightedTokens / TOKENS_PER_CREDIT
    }
    
    /**
     * Converts raw token counts to credits
     */
    fun toCredits(inputTokens: Long, outputTokens: Long): Long {
        val weightedTokens = (inputTokens * INPUT_WEIGHT) + (outputTokens * OUTPUT_WEIGHT)
        return weightedTokens / TOKENS_PER_CREDIT
    }
    
    /**
     * Converts credits back to approximate token count (for display purposes)
     * This is an estimation assuming average 1.5x weight
     */
    fun toApproximateTokens(credits: Long): Long {
        return credits * TOKENS_PER_CREDIT * 3 / 4 // Approximate inverse with avg 1.5x weight
    }
    
    /**
     * Calculates weighted tokens (before dividing by 1000)
     * Useful for detailed breakdowns
     */
    fun toWeightedTokens(delta: TokenUsageDelta): Long {
        return (delta.promptTokens * INPUT_WEIGHT) + (delta.completionTokens * OUTPUT_WEIGHT)
    }
    
    /**
     * Calculates weighted tokens from DailyUsageData
     */
    fun toWeightedTokens(usage: DailyUsageData): Long {
        return (usage.promptTokens * INPUT_WEIGHT) + (usage.completionTokens * OUTPUT_WEIGHT)
    }
}
