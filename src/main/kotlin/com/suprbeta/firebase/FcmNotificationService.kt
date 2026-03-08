package com.suprbeta.firebase

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FcmNotificationService(private val application: Application) {

    suspend fun sendNotification(
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        withContext(Dispatchers.IO) {
            try {
                val message = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .putAllData(data)
                    .build()

                val messageId = FirebaseMessaging.getInstance().send(message)
                application.log.info("FCM notification sent: $messageId title=$title")
            } catch (e: Exception) {
                application.log.error("Failed to send FCM notification title=$title", e)
            }
        }
    }
}
