package com.suprbeta.browser

interface BrowserClientBridge {
    suspend fun publishEvent(userId: String, payload: BrowserSessionEventPayload)

    fun resolveTaskId(userId: String): String? = null
}

object NoopBrowserClientBridge : BrowserClientBridge {
    override suspend fun publishEvent(userId: String, payload: BrowserSessionEventPayload) = Unit
}
