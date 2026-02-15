package com.suprbeta.firebase

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extension function to convert Firebase ApiFuture to suspend function
 */
private suspend fun <T> ApiFuture<T>.await(): T = withContext(Dispatchers.IO) {
    get()
}

class FirestoreRepository(
    private val firestore: Firestore,
    private val application: Application
) {
    companion object {
        private const val PROVISIONING_COLLECTION = "provisioning_status"
        private const val SESSIONS_COLLECTION = "proxy_sessions"
        private const val USER_DROPLETS_COLLECTION = "user_droplets"
    }

    // ==================== Provisioning Status Operations ====================

    /**
     * Saves or updates a provisioning status document
     */
    suspend fun saveProvisioningStatus(status: ProvisioningStatus) {
        try {
            application.log.debug("Saving provisioning status for droplet: ${status.droplet_id}")

            val docRef = firestore.collection(PROVISIONING_COLLECTION)
                .document(status.droplet_id.toString())

            docRef.set(status).await()

            application.log.debug("Provisioning status saved successfully")
        } catch (e: Exception) {
            application.log.error("Failed to save provisioning status for droplet ${status.droplet_id}", e)
            throw e
        }
    }

    /**
     * Retrieves a provisioning status by droplet ID
     */
    suspend fun getProvisioningStatus(dropletId: Long): ProvisioningStatus? {
        return try {
            application.log.debug("Fetching provisioning status for droplet: $dropletId")

            val docRef = firestore.collection(PROVISIONING_COLLECTION)
                .document(dropletId.toString())

            val snapshot: DocumentSnapshot = docRef.get().await()

            if (snapshot.exists()) {
                snapshot.toObject(ProvisioningStatus::class.java)
            } else {
                application.log.debug("No provisioning status found for droplet: $dropletId")
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch provisioning status for droplet $dropletId", e)
            null
        }
    }

    /**
     * Retrieves all provisioning statuses
     */
    suspend fun getAllProvisioningStatuses(): List<ProvisioningStatus> {
        return try {
            application.log.debug("Fetching all provisioning statuses")

            val snapshot: QuerySnapshot = firestore.collection(PROVISIONING_COLLECTION).get().await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(ProvisioningStatus::class.java)
                } catch (e: Exception) {
                    application.log.warn("Failed to deserialize provisioning status document ${doc.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch all provisioning statuses", e)
            emptyList()
        }
    }

    /**
     * Deletes a provisioning status document
     */
    suspend fun deleteProvisioningStatus(dropletId: Long) {
        try {
            application.log.debug("Deleting provisioning status for droplet: $dropletId")

            firestore.collection(PROVISIONING_COLLECTION)
                .document(dropletId.toString())
                .delete()
                .await()

            application.log.debug("Provisioning status deleted successfully")
        } catch (e: Exception) {
            application.log.error("Failed to delete provisioning status for droplet $dropletId", e)
        }
    }

    // ==================== Session Metadata Operations ====================

    /**
     * Saves session metadata
     */
    suspend fun saveSessionMetadata(sessionId: String, metadata: Map<String, Any>) {
        try {
            application.log.debug("Saving session metadata for session: $sessionId")

            val docRef = firestore.collection(SESSIONS_COLLECTION)
                .document(sessionId)

            docRef.set(metadata).await()

            application.log.debug("Session metadata saved successfully")
        } catch (e: Exception) {
            application.log.error("Failed to save session metadata for $sessionId", e)
        }
    }

    /**
     * Retrieves session metadata by session ID
     */
    suspend fun getSessionMetadata(sessionId: String): Map<String, Any>? {
        return try {
            application.log.debug("Fetching session metadata for session: $sessionId")

            val docRef = firestore.collection(SESSIONS_COLLECTION)
                .document(sessionId)

            val snapshot: DocumentSnapshot = docRef.get().await()

            if (snapshot.exists()) {
                snapshot.data
            } else {
                application.log.debug("No session metadata found for session: $sessionId")
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch session metadata for $sessionId", e)
            null
        }
    }

    /**
     * Deletes session metadata
     */
    suspend fun deleteSessionMetadata(sessionId: String) {
        try {
            application.log.debug("Deleting session metadata for session: $sessionId")

            firestore.collection(SESSIONS_COLLECTION)
                .document(sessionId)
                .delete()
                .await()

            application.log.debug("Session metadata deleted successfully")
        } catch (e: Exception) {
            application.log.error("Failed to delete session metadata for $sessionId", e)
        }
    }

    // ==================== User Droplets Operations ====================

    /**
     * Saves or updates a user's droplet information
     * One user can have only one droplet
     */
    suspend fun saveUserDroplet(droplet: UserDroplet) {
        try {
            application.log.info("Saving droplet for user: ${droplet.userId}")

            val docRef = firestore.collection(USER_DROPLETS_COLLECTION)
                .document(droplet.userId) // Use userId as document ID

            docRef.set(droplet).await()

            application.log.info("✅ User droplet saved successfully: userId=${droplet.userId}, dropletId=${droplet.dropletId}")
        } catch (e: Exception) {
            application.log.error("Failed to save user droplet for user ${droplet.userId}", e)
            throw e
        }
    }

    /**
     * Retrieves a user's droplet by user ID
     * Returns null if user has no droplet
     */
    suspend fun getUserDroplet(userId: String): UserDroplet? {
        return try {
            application.log.debug("Fetching droplet for user: $userId")

            val docRef = firestore.collection(USER_DROPLETS_COLLECTION)
                .document(userId)

            val snapshot: DocumentSnapshot = docRef.get().await()

            if (snapshot.exists()) {
                val droplet = snapshot.toObject(UserDroplet::class.java)
                application.log.debug("Found droplet for user $userId: dropletId=${droplet?.dropletId}")
                droplet
            } else {
                application.log.debug("No droplet found for user: $userId")
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch user droplet for user $userId", e)
            null
        }
    }

    /**
     * Deletes a user's droplet information
     */
    suspend fun deleteUserDroplet(userId: String) {
        try {
            application.log.info("Deleting droplet for user: $userId")

            firestore.collection(USER_DROPLETS_COLLECTION)
                .document(userId)
                .delete()
                .await()

            application.log.info("User droplet deleted successfully for user: $userId")
        } catch (e: Exception) {
            application.log.error("Failed to delete user droplet for user $userId", e)
        }
    }
}
