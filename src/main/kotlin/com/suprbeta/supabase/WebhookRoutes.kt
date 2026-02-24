package com.suprbeta.supabase

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureWebhookRoutes() {
    routing {
        post("/webhooks/tasks") {
            call.receiveText()
            println("hello world")
            call.respond(HttpStatusCode.OK)
        }
    }
}
