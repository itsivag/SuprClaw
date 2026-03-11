package com.suprbeta.supabase

import com.suprbeta.supabase.models.AgentAction
import com.suprbeta.supabase.models.AgentSummary
import com.suprbeta.supabase.models.CreateTaskRequest
import com.suprbeta.supabase.models.Task
import com.suprbeta.supabase.models.TaskAssignee
import com.suprbeta.supabase.models.TaskDetailResponse
import com.suprbeta.supabase.models.TaskDocument
import com.suprbeta.supabase.models.TaskInsert
import com.suprbeta.supabase.models.TaskMessage
import com.suprbeta.supabase.models.TaskStatusHistoryEntry
import com.suprbeta.supabase.models.TaskUpdate
import com.suprbeta.supabase.models.UpdateTaskRequest
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.ktor.server.application.*
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SupabaseTaskRepository(
    private val application: Application
) {
    suspend fun getTasks(client: SupabaseClient): List<Task> {
        return try {
            application.log.debug("Fetching tasks")
            client.from("tasks").select().decodeList<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch tasks", e)
            emptyList()
        }
    }

    suspend fun getTaskDetail(client: SupabaseClient, id: String): TaskDetailResponse? {
        return try {
            application.log.debug("Fetching task detail id=$id")
            coroutineScope {
                val taskDeferred = async {
                    client.from("tasks").select {
                        filter { eq("id", id) }
                    }.decodeSingleOrNull<Task>()
                }
                val messagesDeferred = async {
                    client.from("task_messages").select {
                        filter { eq("task_id", id) }
                    }.decodeList<TaskMessage>()
                }
                val documentsDeferred = async {
                    client.from("task_documents").select {
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
                val allAgentsDeferred = async {
                    client.from("agents").select().decodeList<AgentSummary>()
                }

                val task = taskDeferred.await() ?: return@coroutineScope null
                val messages = messagesDeferred.await()
                val documents = documentsDeferred.await()
                val statusHistory = statusHistoryDeferred.await()
                val assignees = assigneesDeferred.await()
                val actions = actionsDeferred.await()
                val allAgents = allAgentsDeferred.await()

                val agentIds = buildSet {
                    task.createdBy?.let { add(it) }
                    task.lockedBy?.let { add(it) }
                    messages.forEach { add(it.fromAgent) }
                    documents.forEach { it.createdBy?.let { agentId -> add(agentId) } }
                    statusHistory.forEach { it.changedBy?.let { agentId -> add(agentId) } }
                    assignees.forEach {
                        add(it.agentId)
                        it.assignedBy?.let { agentId -> add(agentId) }
                    }
                    actions.forEach { add(it.agentId) }
                }.filter { it.isNotBlank() }

                val agents = allAgents.filter { it.id in agentIds }

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
            application.log.error("Failed to fetch task detail id=$id", e)
            null
        }
    }

    suspend fun getDeliverables(client: SupabaseClient): List<TaskDocument> {
        application.log.debug("Fetching task deliverables")
        return client.from("task_documents").select().decodeList<TaskDocument>()
    }

    suspend fun createTask(client: SupabaseClient, request: CreateTaskRequest): Task {
        try {
            application.log.info("Creating task title=${request.title}")
            return client.from("tasks").insert(
                TaskInsert(
                    title = request.title.trim(),
                    description = request.description,
                    status = request.status.trim(),
                    priority = request.priority,
                    isRecurring = request.isRecurring,
                    cronExpression = request.cronExpression,
                    recurrenceInterval = request.recurrenceInterval,
                    nextRunAt = request.nextRunAt,
                    lastRunAt = request.lastRunAt
                )
            ) {
                select()
                single()
            }.decodeSingle<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to create task title=${request.title}", e)
            throw e
        }
    }

    suspend fun updateTask(client: SupabaseClient, id: String, request: UpdateTaskRequest): Task? {
        try {
            application.log.info("Updating task id=$id")
            return client.from("tasks").update(
                TaskUpdate(
                    title = request.title.trim(),
                    description = request.description,
                    status = request.status.trim(),
                    priority = request.priority,
                    updatedAt = Instant.now().toString(),
                    isRecurring = request.isRecurring,
                    cronExpression = request.cronExpression,
                    recurrenceInterval = request.recurrenceInterval,
                    nextRunAt = request.nextRunAt,
                    lastRunAt = request.lastRunAt
                )
            ) {
                filter { eq("id", id) }
                select()
                single()
            }.decodeSingleOrNull<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to update task id=$id", e)
            throw e
        }
    }

    /**
     * Deleting a task is intentionally a hard delete on `tasks`.
     * Dependent rows must be cleaned up or detached by DB foreign-key actions,
     * not by application-side manual deletes.
     */
    suspend fun deleteTask(client: SupabaseClient, id: String): Task? {
        try {
            application.log.info("Deleting task id=$id")
            return client.from("tasks").delete {
                filter { eq("id", id) }
                select()
                single()
            }.decodeSingleOrNull<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to delete task id=$id", e)
            throw e
        }
    }
}
