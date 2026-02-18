package com.suprbeta.websocket.models

import io.ktor.websocket.*
import kotlinx.coroutines.Job
import java.util.*

/**
 * Represents a proxy session mapping a mobile client to an OpenClaw VPS connection
 */
data class ProxySession(
    val sessionId: String = UUID.randomUUID().toString(),
    val clientSession: DefaultWebSocketSession,
    @field:Volatile var openclawSession: DefaultWebSocketSession? = null,
    var openClawGatewayToken: String? = null,
    val metadata: SessionMetadata,
    var forwardingJobs: Pair<Job, Job>? = null // (inbound job, outbound job)
) {
    val isOpenClawConnected: Boolean
        get() = openclawSession != null

    fun hasActiveForwarding(): Boolean = forwardingJobs != null
}
