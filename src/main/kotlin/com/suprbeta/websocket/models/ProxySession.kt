package com.suprbeta.websocket.models

import io.ktor.websocket.*
import kotlinx.coroutines.Job
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Represents a proxy session mapping a mobile client to an OpenClaw VPS connection
 */
data class ProxySession(
    val sessionId: String = UUID.randomUUID().toString(),
    var clientSession: DefaultWebSocketSession?,
    @field:Volatile var openclawSession: DefaultWebSocketSession? = null,
    var openClawGatewayToken: String? = null,
    val metadata: SessionMetadata,
    var inboundJob: Job? = null,
    var outboundJob: Job? = null,
    val offlineQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    var disconnectJob: Job? = null
) {
    val isOpenClawConnected: Boolean
        get() = openclawSession != null

    fun hasActiveForwarding(): Boolean = outboundJob != null
    
    val isOffline: Boolean
        get() = clientSession == null
}
