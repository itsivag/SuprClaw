package com.suprbeta.chat

import kotlinx.serialization.Serializable

object ChatMessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

object ChatMessageKind {
    const val MESSAGE = "message"
    const val ERROR = "error"
}

object ChatMessageDirection {
    const val INBOUND = "inbound"
    const val OUTBOUND = "outbound"
}

@Serializable
data class ChatThreadView(
    val threadId: String,
    val threadKey: String,
    val taskId: String? = null,
    val agentId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastMessageAt: String,
    val lastRole: String,
    val messageCount: Long,
    val preview: String
)

@Serializable
data class ChatThreadListResponse(
    val count: Int,
    val threads: List<ChatThreadView>,
    val nextCursor: String? = null
)

@Serializable
data class ChatMessageView(
    val id: String,
    val threadId: String,
    val threadKey: String,
    val role: String,
    val kind: String,
    val direction: String,
    val frameId: String? = null,
    val state: String? = null,
    val complete: Boolean = true,
    val taskId: String? = null,
    val agentId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val content: String
)

@Serializable
data class ChatMessageListResponse(
    val count: Int,
    val threadId: String,
    val messages: List<ChatMessageView>,
    val nextCursor: String? = null
)

data class ChatThreadCursor(
    val lastMessageAt: String,
    val threadId: String
)

data class ChatMessageCursor(
    val createdAt: String,
    val messageId: String
)

data class ChatThreadContext(
    val userId: String,
    val threadId: String,
    val threadKey: String,
    val taskId: String? = null,
    val agentId: String? = null
)

data class ChatMessageWrite(
    val userId: String,
    val threadId: String,
    val threadKey: String,
    val taskId: String? = null,
    val agentId: String? = null,
    val id: String,
    val role: String,
    val kind: String,
    val direction: String,
    val frameId: String? = null,
    val state: String? = null,
    val complete: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
    val content: String,
    val rawFrameJson: String
)
