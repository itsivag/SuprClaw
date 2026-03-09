package com.suprbeta.core

import io.ktor.server.application.*
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoServiceTest {
    private val application = mockk<Application>(relaxed = true)

    @Test
    fun `constructor requires encryption key material`() {
        val exception = assertFailsWith<CryptoConfigurationException> {
            CryptoService(application, "")
        }

        assertTrue(exception.message!!.contains("FIRESTORE_ENCRYPTION_KEYSET"))
    }

    @Test
    fun `encrypt and decrypt round trip with valid keyset`() {
        val service = CryptoService(application, CryptoService.generateNewKeyset())

        val encrypted = service.encrypt("super-secret", "user-1")
        val decrypted = service.decrypt(encrypted, "user-1")

        assertTrue(encrypted.startsWith(CryptoService.PREFIX_V1))
        assertEquals("super-secret", decrypted)
    }

    @Test
    fun `decrypt rejects legacy plaintext values`() {
        val service = CryptoService(application, CryptoService.generateNewKeyset())

        val exception = assertFailsWith<CryptoOperationException> {
            service.decrypt("legacy-plaintext", "user-1")
        }

        assertTrue(exception.message!!.contains("unencrypted sensitive value"))
    }
}
