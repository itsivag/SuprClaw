package com.suprbeta.supabase

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import com.suprbeta.supabase.models.TaskListResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureTaskRoutes(
    taskRepository: SupabaseTaskRepository
) {
    routing {
        authenticated {
            route("/api/tasks") {
                /**
                 * GET /api/tasks
                 * Returns all tasks for the authenticated user's schema.
                 */
                get {
                    val user = call.attributes[firebaseUserKey]
                    val schemaName = "user_" + user.uid.replace(Regex("[^a-zA-Z0-9]"), "_")
                    try {
                        val tasks = taskRepository.getTasks(schemaName)
                        call.respond(
                            HttpStatusCode.OK,
                            TaskListResponse(
                                count = tasks.size,
                                tasks = tasks
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching tasks for schema $schemaName", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/tasks/{id}
                 * Returns full task detail: task, messages, documents,
                 * status history, assignees, and agent actions.
                 */
                get("{id}") {
                    val user = call.attributes[firebaseUserKey]
                    val schemaName = "user_" + user.uid.replace(Regex("[^a-zA-Z0-9]"), "_")
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))
                        return@get
                    }

                    try {
                        val detail = taskRepository.getTaskDetail(id, schemaName)
                        if (detail == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, detail)
                        }
                    } catch (e: Exception) {
                        log.error("Error fetching task detail id=$id schema=$schemaName", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }
            }
        }
    }
}
