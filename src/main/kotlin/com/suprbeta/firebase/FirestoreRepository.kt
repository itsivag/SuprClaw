package com.suprbeta.firebase

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.SetOptions
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.websocket.models.TokenUsageDelta
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
        private const val USERS = "users"
        private const val USER_DROPLETS_SUBCOLLECTION = "droplets"
        private const val USER_USAGE_SUBCOLLECTION = "usage"
        private const val USER_MESSAGE_QUEUE_SUBCOLLECTION = "message_queue"
        private const val PROJECT_REFS_COLLECTION = "supabase_project_refs"
    }

    data class QueuedMessage(val docId: String, val payload: String)

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
     * Saves or updates a user's droplet information (internal version with VPS URL)
     * One user can have only one droplet
     */
    suspend fun saveUserDroplet(droplet: UserDropletInternal) {
        try {
            application.log.info("Saving droplet for user: ${droplet.userId}")

            val dropletDocId = droplet.dropletId.toString()
            val docRef = firestore.collection(USERS)
                .document(droplet.userId)
                .collection(USER_DROPLETS_SUBCOLLECTION)
                .document(dropletDocId)

            docRef.set(droplet).await()

            if (droplet.supabaseProjectRef.isNotBlank()) {
                saveProjectRefMapping(droplet.supabaseProjectRef, droplet.userId)
            }

            application.log.info("✅ User droplet saved successfully: userId=${droplet.userId}, dropletId=${droplet.dropletId}")
        } catch (e: Exception) {
            application.log.error("Failed to save user droplet for user ${droplet.userId}", e)
            throw e
        }
    }

    /**
     * Retrieves a user's droplet by user ID (internal version with VPS URL)
     * Returns null if user has no droplet
     * Used by backend services that need access to VPS gateway URL
     */
    suspend fun getUserDropletInternal(userId: String): UserDropletInternal? {
        return try {
            application.log.debug("Fetching internal droplet for user: $userId")

            val dropletDocs = firestore.collection(USERS)
                .document(userId)
                .collection(USER_DROPLETS_SUBCOLLECTION)
                .limit(1)
                .get()
                .await()

            val dropletDoc = dropletDocs.documents.firstOrNull()
            if (dropletDoc == null) {
                application.log.debug("No droplet found for user: $userId")
                return null
            }

            val docRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_DROPLETS_SUBCOLLECTION)
                .document(dropletDoc.id)

            val snapshot: DocumentSnapshot = docRef.get().await()

            if (snapshot.exists()) {
                val droplet = snapshot.toObject(UserDropletInternal::class.java)
                application.log.debug("Found droplet for user $userId: dropletId=${droplet?.dropletId}, doc=${dropletDoc.id}")
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
     * Retrieves a user's droplet by user ID (client-safe version without VPS URL)
     * Returns null if user has no droplet
     * Used by API endpoints that return data to clients
     */
    suspend fun getUserDroplet(userId: String): UserDroplet? {
        return getUserDropletInternal(userId)?.toUserDroplet()
    }

    /**
     * Updates the configuredMcpTools list on a user's droplet document.
     */
    suspend fun updateConfiguredMcpTools(userId: String, dropletId: Long, tools: List<String>) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_DROPLETS_SUBCOLLECTION)
                .document(dropletId.toString())
                .update("configuredMcpTools", tools)
                .await()
            application.log.info("Updated configuredMcpTools for userId=$userId, dropletId=$dropletId: ${tools.joinToString()}")
        } catch (e: Exception) {
            application.log.error("Failed to update configuredMcpTools for userId=$userId, dropletId=$dropletId", e)
            throw e
        }
    }

    /**
     * Deletes a user's droplet information
     */
    suspend fun deleteUserDroplet(userId: String) {
        try {
            application.log.info("Deleting droplet for user: $userId")

            val dropletDocs = firestore.collection(USERS)
                .document(userId)
                .collection(USER_DROPLETS_SUBCOLLECTION)
                .get()
                .await()

            for (dropletDoc in dropletDocs.documents) {
                firestore.collection(USERS)
                    .document(userId)
                    .collection(USER_DROPLETS_SUBCOLLECTION)
                    .document(dropletDoc.id)
                    .delete()
                    .await()
            }

            application.log.info("User droplet deleted successfully for user: $userId")
        } catch (e: Exception) {
            application.log.error("Failed to delete user droplet for user $userId", e)
        }
    }

    // ==================== Message Queue Operations ====================

    suspend fun enqueueMessage(userId: String, payload: String) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_MESSAGE_QUEUE_SUBCOLLECTION)
                .add(
                    mapOf(
                        "payload" to payload,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: Exception) {
            application.log.error("Failed to enqueue message for user $userId", e)
            throw e
        }
    }

    suspend fun getQueuedMessages(userId: String): List<QueuedMessage> {
        return try {
            val snapshot = firestore.collection(USERS)
                .document(userId)
                .collection(USER_MESSAGE_QUEUE_SUBCOLLECTION)
                .orderBy("createdAt")
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                val payload = doc.getString("payload") ?: return@mapNotNull null
                QueuedMessage(docId = doc.id, payload = payload)
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch queued messages for user $userId", e)
            emptyList()
        }
    }

    suspend fun deleteQueuedMessages(userId: String, docIds: List<String>) {
        if (docIds.isEmpty()) return
        try {
            val batch = firestore.batch()
            for (docId in docIds) {
                val docRef = firestore.collection(USERS)
                    .document(userId)
                    .collection(USER_MESSAGE_QUEUE_SUBCOLLECTION)
                    .document(docId)
                batch.delete(docRef)
            }
            batch.commit().await()
        } catch (e: Exception) {
            application.log.error("Failed to delete queued messages for user $userId", e)
            throw e
        }
    }

    // ==================== Supabase Project Ref Mapping ====================

    /** Stores a projectRef → userId mapping for webhook routing. */
    suspend fun saveProjectRefMapping(projectRef: String, userId: String) {
        try {
            firestore.collection(PROJECT_REFS_COLLECTION)
                .document(projectRef)
                .set(mapOf("userId" to userId))
                .await()
            application.log.debug("Saved project ref mapping: $projectRef → $userId")
        } catch (e: Exception) {
            application.log.error("Failed to save project ref mapping for $projectRef", e)
            throw e
        }
    }

    /** Resolves a Supabase projectRef to a UserDropletInternal for webhook routing. */
    suspend fun getUserDropletInternalByProjectRef(projectRef: String): UserDropletInternal? {
        return try {
            val doc = firestore.collection(PROJECT_REFS_COLLECTION)
                .document(projectRef)
                .get()
                .await()
            val userId = doc.getString("userId") ?: return null
            getUserDropletInternal(userId)
        } catch (e: Exception) {
            application.log.error("Failed to look up droplet for projectRef $projectRef", e)
            null
        }
    }

    /** Removes the projectRef → userId mapping during teardown. */
    suspend fun deleteProjectRefMapping(projectRef: String) {
        try {
            firestore.collection(PROJECT_REFS_COLLECTION)
                .document(projectRef)
                .delete()
                .await()
            application.log.debug("Deleted project ref mapping: $projectRef")
        } catch (e: Exception) {
            application.log.error("Failed to delete project ref mapping for $projectRef", e)
        }
    }

    suspend fun incrementUserTokenUsageDaily(
        userId: String,
        dayUtc: String,
        delta: TokenUsageDelta,
        sessionId: String
    ) {
        if (delta.isZero()) return

        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_USAGE_SUBCOLLECTION)
                .document(dayUtc)
                .set(
                    mapOf(
                        "userId" to userId,
                        "dayUtc" to dayUtc,
                        "lastSessionId" to sessionId,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "promptTokens" to FieldValue.increment(delta.promptTokens),
                        "completionTokens" to FieldValue.increment(delta.completionTokens),
                        "totalTokens" to FieldValue.increment(delta.totalTokens),
                        "inboundPromptTokens" to FieldValue.increment(delta.inboundPromptTokens),
                        "inboundCompletionTokens" to FieldValue.increment(delta.inboundCompletionTokens),
                        "inboundTotalTokens" to FieldValue.increment(delta.inboundTotalTokens),
                        "outboundPromptTokens" to FieldValue.increment(delta.outboundPromptTokens),
                        "outboundCompletionTokens" to FieldValue.increment(delta.outboundCompletionTokens),
                        "outboundTotalTokens" to FieldValue.increment(delta.outboundTotalTokens),
                        "usageEvents" to FieldValue.increment(delta.usageEvents)
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            application.log.error(
                "Failed to increment usage for user $userId day=$dayUtc session=$sessionId",
                e
            )
            throw e
        }
    }
}
