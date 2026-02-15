package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.CreateDropletNameRequest
import com.suprbeta.digitalocean.models.CreateDropletResponse
import com.suprbeta.digitalocean.models.DropletInfo
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch

fun Application.configureDropletRoutes(
    digitalOceanService: DigitalOceanService,
    provisioningService: DropletProvisioningService,
    firestoreRepository: com.suprbeta.firebase.FirestoreRepository
) {
    routing {
        authenticated {
            route("/api/droplets") {

                /**
                 * POST /api/droplets
                 * Creates a droplet and kicks off background SSH-based provisioning.
                 * Returns immediately with droplet ID + status URL for polling.
                 */
                post {
                    val user = call.attributes[firebaseUserKey]
                    log.info("Creating droplet for user ${user.uid} (${user.email ?: "no email"})")

                    try {
                        val request = call.receive<CreateDropletNameRequest>()

                    // Phase 1 — Create droplet
                    val result = provisioningService.createAndProvision(request.name)

                    // Launch provisioning in background (phases 2-8)
                    launch {
                        provisioningService.provisionDroplet(
                            dropletId = result.dropletId,
                            password = result.password,
                            geminiApiKey = result.geminiApiKey,
                            userId = user.uid
                        )
                    }

                    // Return immediately with initial status
                    val initialStatus = provisioningService.statuses[result.dropletId]

                    val response = CreateDropletResponse(
                        droplet = DropletInfo(
                            id = result.dropletId,
                            name = request.name,
                            status = initialStatus?.phase ?: ProvisioningStatus.PHASE_CREATING,
                            ip_address = initialStatus?.ip_address
                        ),
                        setup_status_url = "/api/droplets/${result.dropletId}/status",
                        message = initialStatus?.message ?: "Provisioning started. Poll the status URL for progress."
                    )

                    call.respond(HttpStatusCode.Accepted, response)
                } catch (e: Exception) {
                    log.error("Error creating droplet", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Unknown error occurred"))
                    )
                }
            }

                /**
                 * GET /api/droplets/my-droplet
                 * Returns the authenticated user's droplet from Firestore.
                 * Returns 404 if user has no droplet.
                 */
                get("my-droplet") {
                    val user = call.attributes[firebaseUserKey]
                    log.debug("Fetching droplet for user ${user.uid}")

                    try {
                        val userDroplet = firestoreRepository.getUserDroplet(user.uid)

                        if (userDroplet != null) {
                            call.respond(HttpStatusCode.OK, userDroplet)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        }
                    } catch (e: Exception) {
                        log.error("Error fetching user droplet", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error occurred"))
                        )
                    }
                }

                /**
                 * GET /api/droplets/{id}/status
                 * Returns the current provisioning status for a droplet.
                 */
                get("{id}/status") {
                    val user = call.attributes[firebaseUserKey]
                    log.debug("Status request for user ${user.uid}")

                    try {
                        val dropletId = call.parameters["id"]?.toLongOrNull()
                        if (dropletId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid droplet ID"))
                            return@get
                        }

                    val status = provisioningService.statuses[dropletId]
                    if (status == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No provisioning status found for droplet $dropletId"))
                        return@get
                    }

                        call.respond(HttpStatusCode.OK, status)
                    } catch (e: Exception) {
                        log.error("Error fetching provisioning status", e)
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
