package com.suprbeta.websocket.models

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Metadata tracking information for a proxy session
 */
data class SessionMetadata(
    val clientToken: String,
    val userId: String,            // Firebase UID
    val userEmail: String?,        // User email
    val emailVerified: Boolean,    // Email verification status
    val authProvider: String?,     // "google.com" or "apple.com"
    val userTier: String,          // "free", "pro", "max", etc.
    val connectedAt: Instant = Instant.now(),
    val platform: String? = null,
    val lastSessionKey: AtomicReference<String?> = AtomicReference(null),
    val currentWeeklyCredits: AtomicLong = AtomicLong(0L),
    val messagesSent: AtomicLong = AtomicLong(0),
    val messagesReceived: AtomicLong = AtomicLong(0)
) {
    fun incrementSent() = messagesSent.incrementAndGet()
    fun incrementReceived() = messagesReceived.incrementAndGet()

    fun rememberSessionKey(sessionKey: String) = lastSessionKey.set(sessionKey)
    fun getSessionKey(): String? = lastSessionKey.get()

    // Legacy naming kept for existing browser/task bridge callers.
    fun rememberTaskId(taskId: String) = rememberSessionKey(taskId)
    fun getTaskId(): String? = getSessionKey()

    fun incrementWeeklyCredits(amount: Long) = currentWeeklyCredits.addAndGet(amount)

    fun getSentCount() = messagesSent.get()
    fun getReceivedCount() = messagesReceived.get()
}
