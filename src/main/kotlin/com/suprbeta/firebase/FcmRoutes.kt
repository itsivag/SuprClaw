package com.suprbeta.firebase

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class FcmTokenRequest(val token: String)

fun Application.configureFcmRoutes(firestoreRepository: FirestoreRepository) {
    routing {
        authenticated {
            /**
             * PUT /api/fcm/token
             * Registers or updates the FCM token for the authenticated user.
             */
            put("/api/fcm/token") {
                val user = call.attributes[firebaseUserKey]
                val request = runCatching { call.receive<FcmTokenRequest>() }.getOrNull()
                if (request == null || request.token.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "token is required"))
                    return@put
                }
                firestoreRepository.saveFcmToken(user.uid, request.token)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
    }
}
