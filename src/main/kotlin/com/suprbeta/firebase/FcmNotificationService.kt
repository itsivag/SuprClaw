package com.suprbeta.firebase

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PushNotificationSender {
    suspend fun sendNotification(
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        highPriority: Boolean = false
    )
}

class FcmNotificationService(private val application: Application) : PushNotificationSender {

    override suspend fun sendNotification(
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String>,
        highPriority: Boolean
    ) {
        withContext(Dispatchers.IO) {
            try {
                val builder = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .putAllData(data)

                if (highPriority) {
                    builder.setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build()
                    )
                    builder.setApnsConfig(
                        ApnsConfig.builder()
                            .putHeader("apns-priority", "10")
                            .setAps(
                                Aps.builder()
                                    .setContentAvailable(true)
                                    .build()
                            )
                            .build()
                    )
                }

                val message = builder.build()

                val messageId = FirebaseMessaging.getInstance().send(message)
                application.log.info("FCM notification sent: $messageId title=$title")
            } catch (e: Exception) {
                application.log.error("Failed to send FCM notification title=$title", e)
            }
        }
    }

}
