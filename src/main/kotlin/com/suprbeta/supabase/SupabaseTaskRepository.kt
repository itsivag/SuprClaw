package com.suprbeta.supabase

import com.suprbeta.supabase.models.AgentAction
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

                TaskDetailResponse(
                    task = task,
                    messages = messagesDeferred.await(),
                    documents = documentsDeferred.await(),
                    statusHistory = statusHistoryDeferred.await(),
                    assignees = assigneesDeferred.await(),
                    actions = actionsDeferred.await()
                )
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch task detail for id=$id", e)
            null
        }
    }
}
