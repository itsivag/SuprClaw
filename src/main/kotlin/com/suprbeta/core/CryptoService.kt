@file:Suppress("DEPRECATION")
package com.suprbeta.core

import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import io.ktor.server.application.*
import java.util.Base64

class CryptoConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class CryptoOperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Handles field-level encryption for sensitive database fields using Google Tink.
 * Sensitive fields must always be stored as encrypted versioned ciphertext.
 */
class CryptoService(application: Application, keysetJsonBase64Override: String? = null) {
    private val logger = application.log
    private val aead: Aead

    companion object {
        const val PREFIX_V1 = "v1:"

        fun generateNewKeyset(): String {
            AeadConfig.register()
            val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            val baos = java.io.ByteArrayOutputStream()
            CleartextKeysetHandle.write(handle, com.google.crypto.tink.JsonKeysetWriter.withOutputStream(baos))
            return Base64.getEncoder().encodeToString(baos.toByteArray())
        }
    }

    init {
        AeadConfig.register()

        val dotenv = io.github.cdimascio.dotenv.dotenv { ignoreIfMissing = true; directory = "." }
        val keysetJsonBase64 = keysetJsonBase64Override
            ?: dotenv["FIRESTORE_ENCRYPTION_KEYSET"]
            ?: System.getenv("FIRESTORE_ENCRYPTION_KEYSET")

        if (keysetJsonBase64.isNullOrBlank()) {
            throw CryptoConfigurationException("FIRESTORE_ENCRYPTION_KEYSET must be configured before the application starts")
        }

        aead = try {
            val normalizedBase64 = normalizeBase64(keysetJsonBase64)
            val decodedJson = String(Base64.getDecoder().decode(normalizedBase64))
            val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(decodedJson))
            handle.getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            logger.error("Failed to initialize Google Tink Aead", e)
            throw CryptoConfigurationException("Failed to initialize FIRESTORE_ENCRYPTION_KEYSET", e)
        }
    }

    /**
     * Encrypts a plaintext string and returns a versioned ciphertext.
     * Format: v1:base64(ciphertext)
     */
    fun encrypt(plaintext: String?, associatedData: String = ""): String {
        if (plaintext.isNullOrBlank()) return ""

        // If it's already encrypted, don't double encrypt
        if (plaintext.startsWith(PREFIX_V1)) return plaintext

        return try {
            val ciphertext = aead.encrypt(plaintext.toByteArray(), associatedData.toByteArray())
            val base64Cipher = Base64.getEncoder().encodeToString(ciphertext)
            "$PREFIX_V1$base64Cipher"
        } catch (e: Exception) {
            logger.error("Encryption failed", e)
            throw CryptoOperationException("Encryption failed", e)
        }
    }

    /**
     * Decrypts a versioned ciphertext.
     */
    fun decrypt(ciphertext: String?, associatedData: String = ""): String {
        if (ciphertext.isNullOrBlank()) return ""

        if (!ciphertext.startsWith(PREFIX_V1)) {
            throw CryptoOperationException("Encountered unencrypted sensitive value")
        }

        return try {
            val base64Cipher = ciphertext.removePrefix(PREFIX_V1)
            val decodedCipher = Base64.getDecoder().decode(base64Cipher)
            val decryptedBytes = aead.decrypt(decodedCipher, associatedData.toByteArray())
            String(decryptedBytes)
        } catch (e: Exception) {
            logger.error("Decryption failed. The data might be corrupted or the wrong key was used.", e)
            throw CryptoOperationException("Decryption failed for encrypted sensitive value", e)
        }
    }

    private fun normalizeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        return normalized + "=".repeat(padding)
    }
}
