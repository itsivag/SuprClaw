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
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.server.application.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseTaskRepository(
    private val client: SupabaseClient,
    private val application: Application
) {
    suspend fun getTasks(schemaName: String): List<Task> {
        return try {
            application.log.debug("Fetching tasks for schema: $schemaName")
            client.postgrest.rpc("get_user_tasks", buildJsonObject {
                put("p_schema_name", schemaName)
            }).decodeList<Task>()
        } catch (e: Exception) {
            application.log.error("Failed to fetch tasks for schema $schemaName", e)
            emptyList()
        }
    }

    suspend fun getTaskDetail(id: String, schemaName: String): TaskDetailResponse? {
        return try {
            application.log.debug("Fetching task detail id=$id schema=$schemaName")
            coroutineScope {
                val taskDeferred = async {
                    client.postgrest.rpc("get_user_task", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeSingleOrNull<Task>()
                }
                val messagesDeferred = async {
                    client.postgrest.rpc("get_user_task_messages", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeList<TaskMessage>()
                }
                val documentsDeferred = async {
                    client.postgrest.rpc("get_user_task_documents", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeList<TaskDocument>()
                }
                val statusHistoryDeferred = async {
                    client.postgrest.rpc("get_user_task_status_history", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeList<TaskStatusHistoryEntry>()
                }
                val assigneesDeferred = async {
                    client.postgrest.rpc("get_user_task_assignees", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeList<TaskAssignee>()
                }
                val actionsDeferred = async {
                    client.postgrest.rpc("get_user_task_agent_actions", buildJsonObject {
                        put("p_schema_name", schemaName)
                        put("p_task_id", id)
                    }).decodeList<AgentAction>()
                }
                val allAgentsDeferred = async {
                    client.postgrest.rpc("get_user_agents", buildJsonObject {
                        put("p_schema_name", schemaName)
                    }).decodeList<AgentSummary>()
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
                    documents.forEach { it.createdBy?.let { id -> add(id) } }
                    statusHistory.forEach { it.changedBy?.let { id -> add(id) } }
                    assignees.forEach {
                        add(it.agentId)
                        it.assignedBy?.let { id -> add(id) }
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
            application.log.error("Failed to fetch task detail id=$id schema=$schemaName", e)
            null
        }
    }
}
