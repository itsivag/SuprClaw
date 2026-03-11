package com.suprbeta.supabase

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import com.suprbeta.supabase.models.CreateTaskRequest
import com.suprbeta.supabase.models.DeleteTaskResponse
import com.suprbeta.supabase.models.DeliverableListResponse
import com.suprbeta.supabase.models.TaskListResponse
import com.suprbeta.supabase.models.UpdateTaskRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureTaskRoutes(
    taskRepository: SupabaseTaskRepository,
    firestoreRepository: FirestoreRepository,
    userClientProvider: UserSupabaseClientProvider
) {
    routing {
        authenticated {
            route("/api/tasks") {
                /**
                 * GET /api/tasks
                 * Returns all tasks for the authenticated user's Supabase project.
                 */
                get {
                    val user = call.attributes[firebaseUserKey]
                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@get
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val tasks = taskRepository.getTasks(client)
                        call.respond(
                            HttpStatusCode.OK,
                            TaskListResponse(
                                count = tasks.size,
                                tasks = tasks
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching tasks for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * POST /api/tasks
                 * Creates a task in the authenticated user's Supabase project.
                 */
                post {
                    val user = call.attributes[firebaseUserKey]
                    val request = try {
                        call.receive<CreateTaskRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task payload"))
                        return@post
                    }

                    if (request.title.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task title is required"))
                        return@post
                    }
                    if (request.status.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task status is required"))
                        return@post
                    }

                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@post
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val task = taskRepository.createTask(client, request)
                        call.respond(HttpStatusCode.Created, task)
                    } catch (e: Exception) {
                        log.error("Error creating task for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/tasks/deliverables
                 * Returns all deliverables (task_documents) for the authenticated user's Supabase project.
                 */
                get("deliverables") {
                    val user = call.attributes[firebaseUserKey]
                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@get
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val deliverables = taskRepository.getDeliverables(client)
                        call.respond(
                            HttpStatusCode.OK,
                            DeliverableListResponse(
                                count = deliverables.size,
                                deliverables = deliverables
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching deliverables for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * PUT /api/tasks/{id}
                 * Replaces mutable task fields for the specified task.
                 */
                put("{id}") {
                    val user = call.attributes[firebaseUserKey]
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))
                        return@put
                    }

                    val request = try {
                        call.receive<UpdateTaskRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task payload"))
                        return@put
                    }

                    if (request.title.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task title is required"))
                        return@put
                    }
                    if (request.status.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task status is required"))
                        return@put
                    }

                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@put
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val task = taskRepository.updateTask(client, id, request)
                        if (task == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, task)
                        }
                    } catch (e: Exception) {
                        log.error("Error updating task id=$id for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * DELETE /api/tasks/{id}
                 * Deletes the specified task from the authenticated user's Supabase project.
                 * Dependent task-linked rows are expected to be removed or detached by the
                 * schema's foreign-key delete actions rather than route-level cleanup.
                 */
                delete("{id}") {
                    val user = call.attributes[firebaseUserKey]
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))
                        return@delete
                    }

                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@delete
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val task = taskRepository.deleteTask(client, id)
                        if (task == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                        } else {
                            call.respond(
                                HttpStatusCode.OK,
                                DeleteTaskResponse(
                                    message = "Task deleted",
                                    id = task.id ?: id
                                )
                            )
                        }
                    } catch (e: Exception) {
                        log.error("Error deleting task id=$id for user ${user.uid}", e)
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
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))
                        return@get
                    }
                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@get
                    }
                    val client = userClientProvider.getClient(droplet.resolveSupabaseUrl(), droplet.supabaseServiceKey, droplet.supabaseSchema)
                    try {
                        val detail = taskRepository.getTaskDetail(client, id)
                        if (detail == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, detail)
                        }
                    } catch (e: Exception) {
                        log.error("Error fetching task detail id=$id for user ${user.uid}", e)
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
