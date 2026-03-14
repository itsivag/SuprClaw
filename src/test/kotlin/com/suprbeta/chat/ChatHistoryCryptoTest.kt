package com.suprbeta.chat

import com.suprbeta.core.CryptoOperationException
import com.suprbeta.core.CryptoService
import io.ktor.server.application.Application
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatHistoryCryptoTest {
    private val application = mockk<Application>(relaxed = true)
    private val cryptoService = CryptoService(application, CryptoService.generateNewKeyset())

    @Test
    fun `content raw frame and preview round trip`() {
        val encryptedContent = ChatHistoryCrypto.encryptContent(cryptoService, "user-1", "thread-1", "message-1", "hello")
        val encryptedRaw = ChatHistoryCrypto.encryptRawFrame(cryptoService, "user-1", "thread-1", "message-1", """{"event":"chat"}""")
        val encryptedPreview = ChatHistoryCrypto.encryptPreview(cryptoService, "user-1", "thread-1", "preview")

        assertTrue(encryptedContent.startsWith(CryptoService.PREFIX_V1))
        assertTrue(encryptedRaw.startsWith(CryptoService.PREFIX_V1))
        assertTrue(encryptedPreview.startsWith(CryptoService.PREFIX_V1))

        assertEquals("hello", ChatHistoryCrypto.decryptContent(cryptoService, "user-1", "thread-1", "message-1", encryptedContent))
        assertEquals("""{"event":"chat"}""", ChatHistoryCrypto.decryptRawFrame(cryptoService, "user-1", "thread-1", "message-1", encryptedRaw))
        assertEquals("preview", ChatHistoryCrypto.decryptPreview(cryptoService, "user-1", "thread-1", encryptedPreview))
    }

    @Test
    fun `decrypt fails when aad does not match`() {
        val encrypted = ChatHistoryCrypto.encryptContent(cryptoService, "user-1", "thread-1", "message-1", "secret")

        assertFailsWith<CryptoOperationException> {
            ChatHistoryCrypto.decryptContent(cryptoService, "user-1", "thread-1", "message-2", encrypted)
        }
    }
}
