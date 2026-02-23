package com.suprbeta.supabase

import com.suprbeta.firebase.authenticated
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
                 * Returns all tasks.
                 */
                get {
                    try {
                        val tasks = taskRepository.getTasks()
                        call.respond(
                            HttpStatusCode.OK,
                            TaskListResponse(
                                count = tasks.size,
                                tasks = tasks
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching tasks", e)
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
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))
                        return@get
                    }

                    try {
                        val detail = taskRepository.getTaskDetail(id)
                        if (detail == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, detail)
                        }
                    } catch (e: Exception) {
                        log.error("Error fetching task detail for id=$id", e)
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
