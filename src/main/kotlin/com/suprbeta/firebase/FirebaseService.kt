package com.suprbeta.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import java.io.FileInputStream

class FirebaseService(
    private val application: Application
) {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val credentialsPath = dotenv["FIREBASE_CREDENTIALS_PATH"]
        ?: throw IllegalStateException("FIREBASE_CREDENTIALS_PATH not found in environment")

    private val projectId = dotenv["FIREBASE_PROJECT_ID"]
        ?: throw IllegalStateException("FIREBASE_PROJECT_ID not found in environment")

    private val firebaseApp: FirebaseApp

    init {
        application.log.info("Initializing Firebase with project: $projectId")

        try {
            val serviceAccount = FileInputStream(credentialsPath)
            val credentials = GoogleCredentials.fromStream(serviceAccount)

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build()

            firebaseApp = FirebaseApp.initializeApp(options)
            application.log.info("Firebase initialized successfully")
        } catch (e: Exception) {
            application.log.error("Failed to initialize Firebase", e)
            throw e
        }
    }

    val firestore: Firestore by lazy {
        application.log.info("Getting Firestore instance")
        FirestoreClient.getFirestore(firebaseApp)
    }

    fun shutdown() {
        try {
            application.log.info("Shutting down Firebase")
            firebaseApp.delete()
            application.log.info("Firebase shutdown complete")
        } catch (e: Exception) {
            application.log.error("Error during Firebase shutdown", e)
        }
    }
}
