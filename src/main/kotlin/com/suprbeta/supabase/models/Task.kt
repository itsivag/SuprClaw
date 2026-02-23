package com.suprbeta.supabase.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Task(
    val id: String? = null,
    val title: String = "",
    val description: String? = null,
    val status: String = "inbox",
    val priority: Int = 5,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("locked_by") val lockedBy: String? = null,
    @SerialName("locked_at") val lockedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class TaskMessage(
    val id: String? = null,
    @SerialName("task_id") val taskId: String = "",
    @SerialName("from_agent") val fromAgent: String = "",
    val content: String = "",
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TaskDocument(
    val id: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val title: String = "",
    val content: String = "",
    val version: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class TaskStatusHistoryEntry(
    val id: String? = null,
    @SerialName("task_id") val taskId: String = "",
    @SerialName("from_status") val fromStatus: String? = null,
    @SerialName("to_status") val toStatus: String = "",
    @SerialName("changed_by") val changedBy: String? = null,
    val reason: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TaskAssignee(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("assigned_at") val assignedAt: String? = null,
    @SerialName("assigned_by") val assignedBy: String? = null
)

@Serializable
data class AgentAction(
    val id: String? = null,
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("task_id") val taskId: String? = null,
    val action: String = "",
    val meta: JsonElement = JsonObject(emptyMap()),
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TaskDetailResponse(
    val task: Task,
    val messages: List<TaskMessage>,
    val documents: List<TaskDocument>,
    @SerialName("status_history") val statusHistory: List<TaskStatusHistoryEntry>,
    val assignees: List<TaskAssignee>,
    val actions: List<AgentAction>
)

@Serializable
data class TaskListResponse(
    val count: Int,
    val tasks: List<Task>
)
