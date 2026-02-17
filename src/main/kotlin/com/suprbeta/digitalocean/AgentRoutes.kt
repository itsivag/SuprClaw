package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.CreateAgentRequest
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureAgentRoutes(
    configuringService: DropletConfigurationService,
    firestoreRepository: FirestoreRepository
) {
    routing {
        authenticated {
            route("/api/agents") {
                /**
                 * GET /api/agents
                 * Returns all agents for the authenticated user.
                 */
                get {
                    val user = call.attributes[firebaseUserKey]

                    try {
                        val agents = firestoreRepository.getUserAgents(user.uid)
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "userId" to user.uid,
                                "count" to agents.size,
                                "agents" to agents
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching agents for user ${user.uid}", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * POST /api/agents/{id}
                 * Creates an agent on the specified droplet through SSH.
                 */
                post("{id}") {
                    val user = call.attributes[firebaseUserKey]
                    log.info("Create agent request for user ${user.uid}")

                    try {
                        val dropletId = call.requireOwnedDropletId(user.uid, firestoreRepository) ?: return@post

                        val request = call.receive<CreateAgentRequest>()
                        val output = configuringService.createAgent(user.uid, request.name)

                        call.respond(
                            HttpStatusCode.Created,
                            mapOf(
                                "dropletId" to dropletId,
                                "name" to request.name,
                                "message" to "Agent created",
                                "output" to output.take(500)
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Invalid request"))
                        )
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to (e.message ?: "Agent creation failed"))
                        )
                    } catch (e: Exception) {
                        log.error("Error creating agent", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * DELETE /api/agents/{id}/{name}
                 * Deletes an agent from the specified droplet through SSH.
                 */
                delete("{id}/{name}") {
                    val user = call.attributes[firebaseUserKey]
                    log.info("Delete agent request for user ${user.uid}")

                    try {
                        val dropletId = call.requireOwnedDropletId(user.uid, firestoreRepository) ?: return@delete

                        val agentName = call.parameters["name"].orEmpty()
                        if (agentName.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid agent name"))
                            return@delete
                        }

                        val output = configuringService.deleteAgent(user.uid, agentName)
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "dropletId" to dropletId,
                                "name" to agentName,
                                "message" to "Agent deleted",
                                "output" to output.take(500)
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Invalid request"))
                        )
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to (e.message ?: "Agent deletion failed"))
                        )
                    } catch (e: Exception) {
                        log.error("Error deleting agent", e)
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

private suspend fun ApplicationCall.requireOwnedDropletId(
    userId: String,
    firestoreRepository: FirestoreRepository
): Long? {
    val dropletId = parameters["id"]?.toLongOrNull()
    if (dropletId == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid droplet ID"))
        return null
    }

    val userDroplet = firestoreRepository.getUserDropletInternal(userId)
    if (userDroplet == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
        return null
    }

    if (userDroplet.dropletId != dropletId) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "Droplet does not belong to user"))
        return null
    }

    return dropletId
}
