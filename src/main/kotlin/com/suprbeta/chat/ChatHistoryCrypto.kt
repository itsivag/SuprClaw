package com.suprbeta.chat

import com.suprbeta.core.CryptoService

internal object ChatHistoryCrypto {
    fun encryptContent(cryptoService: CryptoService, userId: String, threadId: String, messageId: String, value: String): String =
        cryptoService.encrypt(value, contentAad(userId, threadId, messageId))

    fun decryptContent(cryptoService: CryptoService, userId: String, threadId: String, messageId: String, value: String): String =
        cryptoService.decrypt(value, contentAad(userId, threadId, messageId))

    fun encryptRawFrame(cryptoService: CryptoService, userId: String, threadId: String, messageId: String, value: String): String =
        cryptoService.encrypt(value, rawAad(userId, threadId, messageId))

    fun decryptRawFrame(cryptoService: CryptoService, userId: String, threadId: String, messageId: String, value: String): String =
        cryptoService.decrypt(value, rawAad(userId, threadId, messageId))

    fun encryptPreview(cryptoService: CryptoService, userId: String, threadId: String, value: String): String =
        cryptoService.encrypt(value, previewAad(userId, threadId))

    fun decryptPreview(cryptoService: CryptoService, userId: String, threadId: String, value: String): String =
        cryptoService.decrypt(value, previewAad(userId, threadId))

    internal fun contentAad(userId: String, threadId: String, messageId: String): String =
        "chat-history:$userId:$threadId:$messageId:content"

    internal fun rawAad(userId: String, threadId: String, messageId: String): String =
        "chat-history:$userId:$threadId:$messageId:raw"

    internal fun previewAad(userId: String, threadId: String): String =
        "chat-history:$userId:$threadId:preview"
}
