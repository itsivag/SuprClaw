package com.suprbeta.supabase

import com.suprbeta.supabase.models.AgentAction
import com.suprbeta.supabase.models.AgentSummary
import com.suprbeta.supabase.models.Task
import com.suprbeta.supabase.models.TaskAssignee
import com.suprbeta.supabase.models.TaskDetailResponse
import com.suprbeta.supabase.models.TaskDocument
import com.suprbeta.supabase.models.TaskMessage
import com.suprbeta.supabase.models.TaskStatusHistoryEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SupabaseTaskRepository(
    private val client: SupabaseClient,
    private val application: Application
) {
    suspend fun getTasks(): List<Task> {
        return try {
            application.log.debug("Fetching all tasks")
            client.from("tasks").select().decodeList<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch tasks", e)
            emptyList()
        }
    }

    suspend fun getTaskDetail(id: String): TaskDetailResponse? {
        return try {
            application.log.debug("Fetching task detail for id=$id")
            coroutineScope {
                val taskDeferred = async {
                    client.from("tasks").select {
                        filter { eq("id", id) }
                    }.decodeSingleOrNull<Task>()
                }
                val messagesDeferred = async {
                    client.from("messages").select {
                        filter { eq("task_id", id) }
                    }.decodeList<TaskMessage>()
                }
                val documentsDeferred = async {
                    client.from("documents").select {
                        filter { eq("task_id", id) }
                    }.decodeList<TaskDocument>()
                }
                val statusHistoryDeferred = async {
                    client.from("task_status_history").select {
                        filter { eq("task_id", id) }
                    }.decodeList<TaskStatusHistoryEntry>()
                }
                val assigneesDeferred = async {
                    client.from("task_assignees").select {
                        filter { eq("task_id", id) }
                    }.decodeList<TaskAssignee>()
                }
                val actionsDeferred = async {
                    client.from("agent_actions").select {
                        filter { eq("task_id", id) }
                    }.decodeList<AgentAction>()
                }

                val task = taskDeferred.await() ?: return@coroutineScope null
                val messages = messagesDeferred.await()
                val documents = documentsDeferred.await()
                val statusHistory = statusHistoryDeferred.await()
                val assignees = assigneesDeferred.await()
                val actions = actionsDeferred.await()

                val agentIds = buildSet {
                    task.createdBy?.let { add(it) }
                    task.lockedBy?.let { add(it) }
                    messages.forEach { add(it.fromAgent) }
                    documents.forEach { it.createdBy?.let { id -> add(id) } }
                    statusHistory.forEach { it.changedBy?.let { id -> add(id) } }
                    assignees.forEach {
                        add(it.agentId)
                        it.assignedBy?.let { id -> add(id) }
                    }
                    actions.forEach { add(it.agentId) }
                }.filter { it.isNotBlank() }

                val agents = if (agentIds.isEmpty()) emptyList() else {
                    client.from("agents").select {
                        filter { isIn("id", agentIds) }
                    }.decodeList<AgentSummary>()
                }

                TaskDetailResponse(
                    task = task,
                    messages = messages,
                    documents = documents,
                    statusHistory = statusHistory,
                    assignees = assignees,
                    actions = actions,
                    agents = agents
                )
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch task detail for id=$id", e)
            null
        }
    }
}
