package com.suprbeta.chat

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureChatRoutes(chatHistoryService: ChatHistoryService) {
    routing {
        authenticated {
            route("/api/chat") {
                get("/threads") {
                    val user = call.attributes[firebaseUserKey]
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val cursor = call.request.queryParameters["cursor"]

                    try {
                        call.respond(HttpStatusCode.OK, chatHistoryService.listThreads(user.uid, limit, cursor))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }

                get("/threads/{threadId}/messages") {
                    val user = call.attributes[firebaseUserKey]
                    val threadId = call.parameters["threadId"].orEmpty()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                    val cursor = call.request.queryParameters["cursor"]

                    if (threadId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "threadId is required"))
                        return@get
                    }

                    try {
                        call.respond(HttpStatusCode.OK, chatHistoryService.listMessages(user.uid, threadId, limit, cursor))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }
        }
    }
}
