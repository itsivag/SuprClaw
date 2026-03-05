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

/**
 * Handles field-level encryption for sensitive database fields using Google Tink.
 * Provides versioned encryption so we can gracefully migrate existing plaintext data.
 */
class CryptoService(application: Application) {
    private val logger = application.log
    private val aead: Aead?

    companion object {
        const val PREFIX_V1 = "v1:"
    }

    init {
        AeadConfig.register()
        
        val dotenv = io.github.cdimascio.dotenv.dotenv { ignoreIfMissing = true; directory = "." }
        val keysetJsonBase64 = dotenv["FIRESTORE_ENCRYPTION_KEYSET"] ?: System.getenv("FIRESTORE_ENCRYPTION_KEYSET")
        
        aead = if (!keysetJsonBase64.isNullOrBlank()) {
            try {
                // Replace URL-safe characters with standard ones to prevent "Illegal base64 character" exceptions
                val normalizedBase64 = keysetJsonBase64.replace('-', '+').replace('_', '/')
                val decodedJson = String(Base64.getDecoder().decode(normalizedBase64))
                val handle = CleartextKeysetHandle.read(JsonKeysetReader.withString(decodedJson))
                handle.getPrimitive(Aead::class.java)
            } catch (e: Exception) {
                logger.error("Failed to initialize Google Tink Aead. Encryption will be disabled.", e)
                null
            }
        } else {
            logger.warn("FIRESTORE_ENCRYPTION_KEYSET not set. Sensitive fields will be stored in PLAINTEXT. To fix this, generate a keyset and set the environment variable.")
            null
        }
    }

    /**
     * Encrypts a plaintext string and returns a versioned ciphertext.
     * Format: v1:base64(ciphertext)
     * If Tink is not configured or plaintext is blank, it returns the plaintext.
     */
    fun encrypt(plaintext: String?, associatedData: String = ""): String {
        if (plaintext.isNullOrBlank()) return ""
        if (aead == null) return plaintext
        
        // If it's already encrypted, don't double encrypt
        if (plaintext.startsWith(PREFIX_V1)) return plaintext

        return try {
            val ciphertext = aead.encrypt(plaintext.toByteArray(), associatedData.toByteArray())
            val base64Cipher = Base64.getEncoder().encodeToString(ciphertext)
            "$PREFIX_V1$base64Cipher"
        } catch (e: Exception) {
            logger.error("Encryption failed, falling back to plaintext", e)
            plaintext
        }
    }

    /**
     * Decrypts a versioned ciphertext.
     * If the string does not start with the version prefix (e.g. "v1:"), 
     * it is assumed to be legacy plaintext and returned as-is.
     */
    fun decrypt(ciphertext: String?, associatedData: String = ""): String {
        if (ciphertext.isNullOrBlank()) return ""
        
        if (!ciphertext.startsWith(PREFIX_V1)) {
            // Legacy plaintext fallback
            return ciphertext
        }
        
        if (aead == null) {
            logger.error("Cannot decrypt $PREFIX_V1 field because Tink is not configured. Returning raw ciphertext.")
            return ciphertext
        }

        return try {
            val base64Cipher = ciphertext.removePrefix(PREFIX_V1)
            val decodedCipher = Base64.getDecoder().decode(base64Cipher)
            val decryptedBytes = aead.decrypt(decodedCipher, associatedData.toByteArray())
            String(decryptedBytes)
        } catch (e: Exception) {
            logger.error("Decryption failed. The data might be corrupted or the wrong key was used.", e)
            // In a real app you might throw, but to prevent crashing the whole app, we return the raw string 
            // or an empty string depending on security requirements. Here we return the raw string so it's not lost.
            ciphertext
        }
    }
    
    /**
     * Helper to generate a new keyset for the .env file
     */
    fun generateNewKeyset(): String {
        AeadConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val baos = java.io.ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, com.google.crypto.tink.JsonKeysetWriter.withOutputStream(baos))
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
