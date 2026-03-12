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
    val activeTaskId: AtomicReference<String?> = AtomicReference(null),
    val currentWeeklyCredits: AtomicLong = AtomicLong(0L),
    val messagesSent: AtomicLong = AtomicLong(0),
    val messagesReceived: AtomicLong = AtomicLong(0)
) {
    fun incrementSent() = messagesSent.incrementAndGet()
    fun incrementReceived() = messagesReceived.incrementAndGet()

    fun rememberTaskId(taskId: String) = activeTaskId.set(taskId)
    fun getTaskId(): String? = activeTaskId.get()

    fun incrementWeeklyCredits(amount: Long) = currentWeeklyCredits.addAndGet(amount)

    fun getSentCount() = messagesSent.get()
    fun getReceivedCount() = messagesReceived.get()
}
