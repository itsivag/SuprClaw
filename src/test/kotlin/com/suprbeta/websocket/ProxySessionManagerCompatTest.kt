package com.suprbeta.websocket

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxySessionManagerCompatTest {
    @Test
    fun `buildPicoClawAbortAck returns success no-op response`() {
        val frame = buildPicoClawAbortAck(requestId = "7", sessionKey = "agent:main:main")

        val result = requireNotNull(frame.result).jsonObject
        assertEquals("res", frame.type)
        assertEquals("7", frame.id)
        assertTrue(frame.ok == true)
        assertEquals("agent:main:main", result["sessionKey"]?.jsonPrimitive?.content)
        assertTrue(result["accepted"]?.jsonPrimitive?.content == "true")
        assertFalse(result["cancelled"]?.jsonPrimitive?.content == "true")
    }
}
