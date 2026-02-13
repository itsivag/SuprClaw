package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.CreateDropletNameRequest
import com.suprbeta.digitalocean.models.CreateDropletResponse
import com.suprbeta.digitalocean.models.DropletInfo
import com.suprbeta.digitalocean.models.ProvisioningStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureDropletRoutes(
    digitalOceanService: DigitalOceanService,
    provisioningService: DropletProvisioningService
) {
    routing {
        route("/api/droplets") {

            /**
             * POST /api/droplets
             * Creates a droplet and kicks off background SSH-based provisioning.
             * Returns immediately with droplet ID + status URL for polling.
             */
            post {
                try {
                    val request = call.receive<CreateDropletNameRequest>()

                    // Phase 1 — Create droplet
                    val result = provisioningService.createAndProvision(request.name)

                    // Run provisioning (phases 2-8) — blocks until complete
                    provisioningService.provisionDroplet(
                        dropletId = result.dropletId,
                        password = result.password,
                        geminiApiKey = result.geminiApiKey
                    )

                    val finalStatus = provisioningService.statuses[result.dropletId]

                    val response = CreateDropletResponse(
                        droplet = DropletInfo(
                            id = result.dropletId,
                            name = request.name,
                            status = finalStatus?.phase ?: "unknown",
                            ip_address = finalStatus?.ip_address
                        ),
                        setup_status_url = "/api/droplets/${result.dropletId}/status",
                        message = finalStatus?.message ?: "Provisioning finished."
                    )

                    if (finalStatus?.phase == ProvisioningStatus.PHASE_COMPLETE) {
                        call.respond(HttpStatusCode.Created, response)
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, response)
                    }
                } catch (e: Exception) {
                    log.error("Error creating droplet", e)
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
