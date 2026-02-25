package com.suprbeta.supabase

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import com.suprbeta.supabase.models.TaskListResponse
import io.ktor.http.*
import io.ktor.server.application.*
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
                    val supabaseUrl = "https://${droplet.supabaseProjectRef}.supabase.co"
                    val client = userClientProvider.getClient(supabaseUrl, droplet.supabaseServiceKey)
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
                    val supabaseUrl = "https://${droplet.supabaseProjectRef}.supabase.co"
                    val client = userClientProvider.getClient(supabaseUrl, droplet.supabaseServiceKey)
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
