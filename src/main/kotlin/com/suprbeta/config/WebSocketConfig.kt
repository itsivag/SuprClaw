package com.suprbeta.config

/**
 * WebSocket configuration constants
 */
object WebSocketConfig {
    const val OPENCLAW_HOST = "167.172.117.2"
    const val OPENCLAW_PORT = 80
    const val OPENCLAW_WS_URL = "ws://$OPENCLAW_HOST/ws"

    const val PING_INTERVAL_MS = 30000L // 30 seconds
    const val TIMEOUT_MS = 60000L // 60 seconds
    const val MAX_FRAME_SIZE = 1024 * 1024 // 1 MB
}
