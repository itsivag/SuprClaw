@file:Suppress("DEPRECATION")
package com.suprbeta.core

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import java.util.Base64

/**
 * Utility to generate a new Google Tink Keyset for the FIRESTORE_ENCRYPTION_KEYSET environment variable.
 */
fun main() {
    AeadConfig.register()
    val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    val baos = java.io.ByteArrayOutputStream()
    
    // Note: KeysetWriter is technically deprecated in favor of specific streams but remains standard for local utilities.
    @Suppress("DEPRECATION")
    CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(baos))
    
    val keyset = Base64.getEncoder().encodeToString(baos.toByteArray())
    
    println("\n=== NEW ENCRYPTION KEYSET GENERATED ===")
    println("Add the following line to your .env file:")
    println("\nFIRESTORE_ENCRYPTION_KEYSET=$keyset")
    println("\n=======================================\n")
}
