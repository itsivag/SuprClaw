package com.suprbeta.connector

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
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
                get {
                    val user = call.attributes[firebaseUserKey]
                    val connectors = connectorService.listConnectors(user.uid)
                    call.respond(HttpStatusCode.OK, ConnectorListResponse(connectors))
                }

                post("/apps/session") {
                    val user = call.attributes[firebaseUserKey]
                    try {
                        val session = connectorService.startConnectorSession(user.uid)
                        call.respond(HttpStatusCode.OK, session)
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector session flow is not configured")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to start connector session for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to start connector session")))
                    }
                }

                get("/apps/session/{sessionId}") {
                    val user = call.attributes[firebaseUserKey]
                    val sessionId = call.parameters["sessionId"].orEmpty()
                    if (sessionId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                        return@get
                    }
                    try {
                        val status = connectorService.getConnectorSessionStatus(user.uid, sessionId)
                        call.respond(HttpStatusCode.OK, status)
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get connector session status for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to get session status")))
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

        route("/api/connectors/apps") {
            get("/connect/{sessionId}") {
                val sessionId = call.parameters["sessionId"].orEmpty()
                val state = call.request.queryParameters["state"].orEmpty()
                if (sessionId.isBlank() || state.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId or state"))
                    return@get
                }
                try {
                    val redirectUrl = connectorService.resolveSessionConnectRedirect(sessionId, state)
                    call.respondRedirect(redirectUrl, permanent = false)
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid session state")))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector connect flow is not configured")))
                } catch (e: Exception) {
                    call.application.log.error("Failed to resolve connector connect redirect", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to resolve connect URL")))
                }
            }

            get("/callback") {
                call.handleConnectorCallback(connectorService)
            }

            post("/callback") {
                call.handleConnectorCallback(connectorService)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.handleConnectorCallback(connectorService: ConnectorService) {
    val query = request.queryParameters
    val form: Parameters = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
    val state = query["state"] ?: form["state"] ?: ""
    val mcpServerUrl = query["mcpServerUrl"]
        ?: query["mcp_server_url"]
        ?: query["server_url"]
        ?: form["mcpServerUrl"]
        ?: form["mcp_server_url"]
        ?: form["server_url"]

    if (state.isBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing state"))
        return
    }

    try {
        val callback = connectorService.finalizeConnectorCallback(state, mcpServerUrl)
        val redirect = connectorService.callbackRedirectUrl(callback)
        if (redirect != null) {
            respondRedirect(redirect, permanent = false)
        } else {
            respond(HttpStatusCode.OK, callback)
        }
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid callback request")))
    } catch (e: Exception) {
        application.log.error("Failed to finalize connector callback", e)
        respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to finalize callback")))
    }
}
