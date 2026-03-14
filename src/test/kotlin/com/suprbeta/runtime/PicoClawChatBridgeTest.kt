package com.suprbeta.runtime

import com.suprbeta.core.SshCommandExecutor
import com.suprbeta.digitalocean.models.UserDropletInternal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class PicoClawChatBridgeTest {
    private val sshCommandExecutor = mockk<SshCommandExecutor>()
    private val runtimeCommandExecutor = RuntimeCommandExecutor(sshCommandExecutor)
    private val bridge = PicoClawChatBridge(AgentRuntimeRegistry(), runtimeCommandExecutor)

    @Test
    fun `runChat strips picoclaw banner and logs from stdout`() {
        val rawOutput = """
            [1;38;2;62;93;185m██████╗ ██╗ ██████╗ ██████╗ [0m
            16:00:35 INF Registered agent agent_id=main
            16:00:35 INF Processing message from cli:cron: Reply with exactly OK

            🦞 

            OK
        """.trimIndent()

        every { sshCommandExecutor.runSshCommand("10.0.0.5", any()) } returns rawOutput

        val frame = bridge.runChat(
            droplet = UserDropletInternal(
                userId = "user-1",
                ipAddress = "10.0.0.5",
                vpsGatewayUrl = "https://tenant.suprclaw.com"
            ),
            sessionKey = "agent:main:main",
            message = "Reply with exactly OK",
            requestId = "req-1"
        )

        val payload = requireNotNull(frame.payload).jsonObject
        assertEquals("OK", payload["text"]?.jsonPrimitive?.content)
        assertEquals("OK", payload["content"]?.jsonPrimitive?.content)
        assertEquals("agent:main:main", payload["sessionKey"]?.jsonPrimitive?.content)
        verify(exactly = 1) { sshCommandExecutor.runSshCommand("10.0.0.5", any()) }
    }

    @Test
    fun `normalizeOutput falls back to last non blank block when prompt is absent`() {
        val rawOutput = """
            startup log

            another log line

            Final answer
            with details
        """.trimIndent()

        assertEquals("Final answer\nwith details", bridge.normalizeOutput(rawOutput))
    }
}
