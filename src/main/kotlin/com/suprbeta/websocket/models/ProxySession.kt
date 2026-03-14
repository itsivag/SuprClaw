package com.suprbeta.websocket.models

import io.ktor.websocket.*
import kotlinx.coroutines.Job
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Represents a proxy session mapping a mobile client to a tenant runtime session
 */
data class ProxySession(
    val sessionId: String = UUID.randomUUID().toString(),
    var clientSession: DefaultWebSocketSession?,
    val metadata: SessionMetadata,
    var inboundJob: Job? = null,
    val offlineQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    var disconnectJob: Job? = null
) {
    val isOffline: Boolean
        get() = clientSession == null
}
