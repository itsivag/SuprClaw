package com.suprbeta.marketplace

import com.suprbeta.digitalocean.DropletConfigurationService
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File

fun Application.configureMarketplaceRoutes(configuringService: DropletConfigurationService) {
    val json = Json { ignoreUnknownKeys = true }

    routing {
        authenticated {
            /**
             * GET /marketplace
             * Returns all available agents in the marketplace.
             */
            get("/marketplace") {
                val file = File("marketplace/agents.json")
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Marketplace agents not found"))
                    return@get
                }
                call.respondText(file.readText(), ContentType.Application.Json, HttpStatusCode.OK)
            }

            /**
             * POST /marketplace/{id}
             * Installs a marketplace agent onto the authenticated user's VPS.
             */
            post("/marketplace/{id}") {
                val user = call.attributes[firebaseUserKey]
                val agentId = call.parameters["id"].orEmpty()

                if (agentId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agent id"))
                    return@post
                }

                try {
                    val file = File("marketplace/agents.json")
                    if (!file.exists()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Marketplace agents not found"))
                        return@post
                    }

                    val catalog = json.decodeFromString<MarketplaceCatalog>(file.readText())
                    val agent = catalog.agents.find { it.id == agentId }
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Agent '$agentId' not found in marketplace")
                        )

                    configuringService.installMarketplaceAgent(user.uid, agent, catalog.repo)

                    call.respond(HttpStatusCode.Created, mapOf("message" to "Agent '${agent.name}' installed successfully"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Installation failed")))
                } catch (e: Exception) {
                    log.error("Error installing marketplace agent '$agentId' for user ${user.uid}", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                }
            }
        }
    }
}
