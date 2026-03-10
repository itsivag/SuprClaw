package com.suprbeta.connector

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureConnectorRoutes(connectorService: ConnectorService) {
    routing {
        authenticated {
            route("/api/connectors") {
                get("/zapier/embed-config") {
                    try {
                        val config = connectorService.getZapierEmbedConfig()
                        call.respond(HttpStatusCode.OK, config)
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Zapier embed is not configured")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to load Zapier embed config", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to load embed config"))
                    }
                }

                get {
                    val user = call.attributes[firebaseUserKey]
                    val connectors = connectorService.listConnectors(user.uid)
                    call.respond(HttpStatusCode.OK, ConnectorListResponse(connectors))
                }

                post("/zapier") {
                    val user = call.attributes[firebaseUserKey]
                    try {
                        val request = call.receive<ConnectZapierRequest>()
                        val connector = connectorService.connectZapier(user.uid, request)
                        call.respond(HttpStatusCode.Created, connector)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector credentials are not configured")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to connect Zapier connector for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to connect connector")))
                    }
                }

                post("/zapier/complete") {
                    val user = call.attributes[firebaseUserKey]
                    try {
                        val request = call.receive<ConnectZapierRequest>()
                        val connector = connectorService.connectZapier(user.uid, request)
                        call.respond(HttpStatusCode.Created, connector)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector credentials are not configured")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to connect Zapier connector for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to connect connector")))
                    }
                }

                put("{provider}/policy") {
                    val user = call.attributes[firebaseUserKey]
                    val provider = call.parameters["provider"].orEmpty()
                    if (provider.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
                        return@put
                    }
                    try {
                        val request = call.receive<UpdateConnectorPolicyRequest>()
                        val connector = connectorService.updateConnectorPolicy(user.uid, provider, request.allowedAgents)
                        call.respond(HttpStatusCode.OK, connector)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Connector not found")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update connector policy for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to update policy")))
                    }
                }

                delete("{provider}") {
                    val user = call.attributes[firebaseUserKey]
                    val provider = call.parameters["provider"].orEmpty()
                    if (provider.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
                        return@delete
                    }
                    try {
                        connectorService.disconnectConnector(user.uid, provider)
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Connector disconnected"))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to disconnect connector '$provider' for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to disconnect connector")))
                    }
                }
            }
        }
    }
}
