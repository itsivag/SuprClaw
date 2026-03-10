package com.suprbeta.supabase

import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import com.suprbeta.supabase.models.NotificationListResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureNotificationRoutes(
    notificationRepository: NotificationRepository,
    firestoreRepository: FirestoreRepository,
    userClientProvider: UserSupabaseClientProvider
) {
    routing {
        authenticated {
            route("/api/notifications") {
                get {
                    val user = call.attributes[firebaseUserKey]
                    val droplet = firestoreRepository.getUserDropletInternal(user.uid)
                    if (droplet == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No droplet found for user"))
                        return@get
                    }

                    val client = userClientProvider.getClient(
                        droplet.resolveSupabaseUrl(),
                        droplet.supabaseServiceKey,
                        droplet.supabaseSchema
                    )

                    try {
                        val notifications = notificationRepository.getNotifications(client)
                        call.respond(
                            HttpStatusCode.OK,
                            NotificationListResponse(
                                count = notifications.size,
                                notifications = notifications
                            )
                        )
                    } catch (e: Exception) {
                        log.error("Error fetching notifications for user ${user.uid}", e)
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
