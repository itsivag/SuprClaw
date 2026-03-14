package com.suprbeta.firebase

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.SetOptions
import com.suprbeta.chat.ChatHistoryCrypto
import com.suprbeta.chat.ChatMessageCursor
import com.suprbeta.chat.ChatMessageView
import com.suprbeta.chat.ChatMessageWrite
import com.suprbeta.chat.ChatThreadCursor
import com.suprbeta.chat.ChatThreadView
import com.suprbeta.browser.BrowserProfileInternal
import com.suprbeta.browser.BrowserSessionInternal
import com.suprbeta.browser.BrowserSessionState
import com.suprbeta.connector.ConnectorInternal
import com.suprbeta.connector.ConnectorSessionInternal
import com.suprbeta.core.CryptoOperationException
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.digitalocean.models.UserDroplet
import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.podman.models.HostInfo
import com.suprbeta.usage.CreditCalculator
import com.suprbeta.usage.DailyUsageData
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
    private val application: Application,
    private val cryptoService: com.suprbeta.core.CryptoService
) {
    companion object {
        private const val PROVISIONING_COLLECTION = "provisioning_status"
        private const val SESSIONS_COLLECTION = "proxy_sessions"
        private const val USERS = "users"
        private const val USER_DROPLETS_SUBCOLLECTION = "droplets"
        private const val USER_USAGE_SUBCOLLECTION = "usage"
        private const val USER_MESSAGE_QUEUE_SUBCOLLECTION = "message_queue"
        private const val USER_CHAT_THREADS_SUBCOLLECTION = "chat_threads"
        private const val USER_CONNECTORS_SUBCOLLECTION = "connectors"
        private const val USER_BROWSER_PROFILES_SUBCOLLECTION = "browser_profiles"
        private const val CHAT_MESSAGES_SUBCOLLECTION = "messages"
        private const val CONNECTOR_SESSIONS_COLLECTION = "connector_sessions"
        private const val BROWSER_SESSIONS_COLLECTION = "browser_sessions"
        private const val PROJECT_REFS_COLLECTION = "supabase_project_refs"
        private const val HOSTS_COLLECTION = "hosts"
        private const val USER_HOST_MAPPINGS_COLLECTION = "user_host_mappings"
        private const val DELETE_BATCH_SIZE = 400
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

    private fun encryptDroplet(droplet: UserDropletInternal): UserDropletInternal {
        return droplet.copy(
            gatewayToken = cryptoService.encrypt(droplet.gatewayToken, droplet.userId),
            sshKey = cryptoService.encrypt(droplet.sshKey, droplet.userId),
            supabaseServiceKey = cryptoService.encrypt(droplet.supabaseServiceKey, droplet.userId)
        )
    }

    private fun decryptDroplet(droplet: UserDropletInternal): UserDropletInternal {
        return droplet.copy(
            gatewayToken = cryptoService.decrypt(droplet.gatewayToken, droplet.userId),
            sshKey = cryptoService.decrypt(droplet.sshKey, droplet.userId),
            supabaseServiceKey = cryptoService.decrypt(droplet.supabaseServiceKey, droplet.userId)
        )
    }

    private fun mapUserDropletInternal(snapshot: DocumentSnapshot): UserDropletInternal? {
        if (!snapshot.exists()) return null

        val configuredMcpTools = (snapshot.get("configuredMcpTools") as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()

        return UserDropletInternal(
            userId = snapshot.getString("userId") ?: snapshot.reference.parent.parent?.id.orEmpty(),
            dropletId = snapshot.getLong("dropletId") ?: snapshot.id.toLongOrNull() ?: 0L,
            dropletName = snapshot.getString("dropletName").orEmpty(),
            gatewayUrl = snapshot.getString("gatewayUrl").orEmpty(),
            vpsGatewayUrl = snapshot.getString("vpsGatewayUrl").orEmpty(),
            gatewayToken = snapshot.getString("gatewayToken").orEmpty(),
            sshKey = snapshot.getString("sshKey").orEmpty(),
            ipAddress = snapshot.getString("ipAddress").orEmpty(),
            subdomain = snapshot.getString("subdomain"),
            createdAt = snapshot.getString("createdAt").orEmpty(),
            status = snapshot.getString("status") ?: "active",
            sslEnabled = snapshot.getBoolean("sslEnabled") ?: true,
            supabaseProjectRef = snapshot.getString("supabaseProjectRef").orEmpty(),
            supabaseServiceKey = snapshot.getString("supabaseServiceKey").orEmpty(),
            supabaseUrl = snapshot.getString("supabaseUrl").orEmpty(),
            supabaseSchema = snapshot.getString("supabaseSchema") ?: "public",
            configuredMcpTools = configuredMcpTools,
            deploymentMode = snapshot.getString("deploymentMode").orEmpty(),
            agentRuntime = snapshot.getString("agentRuntime").orEmpty()
                .ifBlank { snapshot.getString("runtime").orEmpty() }
                .ifBlank { "picoclaw" }
        )
    }

    private fun mapHostInfo(snapshot: DocumentSnapshot): HostInfo? {
        if (!snapshot.exists()) return null

        return HostInfo(
            hostId = snapshot.getLong("hostId") ?: snapshot.id.toLongOrNull() ?: 0L,
            hostIp = snapshot.getString("hostIp").orEmpty(),
            totalCapacity = (snapshot.getLong("totalCapacity") ?: 20L).toInt(),
            currentContainers = (snapshot.getLong("currentContainers") ?: 0L).toInt(),
            status = snapshot.getString("status") ?: HostInfo.STATUS_ACTIVE,
            createdAt = snapshot.getString("createdAt").orEmpty(),
            region = snapshot.getString("region").orEmpty()
        )
    }

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

            val encryptedDroplet = encryptDroplet(droplet)
            docRef.set(encryptedDroplet).await()

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
                val droplet = mapUserDropletInternal(snapshot)?.let { decryptDroplet(it) }
                application.log.debug("Found droplet for user $userId: dropletId=${droplet?.dropletId}, doc=${dropletDoc.id}")
                droplet
            } else {
                application.log.debug("No droplet found for user: $userId")
                null
            }
        } catch (e: CryptoOperationException) {
            application.log.error("Failed to decrypt user droplet for user $userId", e)
            throw e
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

    private fun encryptConnector(userId: String, connector: ConnectorInternal): ConnectorInternal {
        val aadPrefix = "$userId:${connector.provider}"
        return connector.copy(
            mcpServerUrl = cryptoService.encrypt(connector.mcpServerUrl, "$aadPrefix:mcpServerUrl")
        )
    }

    private fun decryptConnector(userId: String, connector: ConnectorInternal): ConnectorInternal {
        val aadPrefix = "$userId:${connector.provider}"
        return connector.copy(
            mcpServerUrl = cryptoService.decrypt(connector.mcpServerUrl, "$aadPrefix:mcpServerUrl")
        )
    }

    suspend fun saveUserConnector(userId: String, connector: ConnectorInternal) {
        try {
            if (connector.provider.isBlank()) {
                throw IllegalArgumentException("Connector provider is required")
            }
            val encrypted = encryptConnector(userId, connector)
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_CONNECTORS_SUBCOLLECTION)
                .document(connector.provider.lowercase())
                .set(encrypted)
                .await()
            application.log.info("Saved connector '${connector.provider}' for userId=$userId")
        } catch (e: Exception) {
            application.log.error("Failed to save connector '${connector.provider}' for userId=$userId", e)
            throw e
        }
    }

    suspend fun getUserConnectorInternal(userId: String, provider: String): ConnectorInternal? {
        return try {
            val snapshot = firestore.collection(USERS)
                .document(userId)
                .collection(USER_CONNECTORS_SUBCOLLECTION)
                .document(provider.lowercase())
                .get()
                .await()
            if (!snapshot.exists()) return null
            val connector = snapshot.toObject(ConnectorInternal::class.java) ?: return null
            decryptConnector(userId, connector)
        } catch (e: CryptoOperationException) {
            application.log.error("Failed to decrypt connector '$provider' for userId=$userId", e)
            throw e
        } catch (e: Exception) {
            application.log.error("Failed to get connector '$provider' for userId=$userId", e)
            null
        }
    }

    suspend fun listUserConnectorsInternal(userId: String): List<ConnectorInternal> {
        return try {
            val snapshot = firestore.collection(USERS)
                .document(userId)
                .collection(USER_CONNECTORS_SUBCOLLECTION)
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val connector = doc.toObject(ConnectorInternal::class.java)
                runCatching { decryptConnector(userId, connector) }
                    .onFailure { application.log.error("Failed to decrypt connector ${doc.id} for userId=$userId", it) }
                    .getOrNull()
            }
        } catch (e: Exception) {
            application.log.error("Failed to list connectors for userId=$userId", e)
            emptyList()
        }
    }

    suspend fun deleteUserConnector(userId: String, provider: String) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_CONNECTORS_SUBCOLLECTION)
                .document(provider.lowercase())
                .delete()
                .await()
            application.log.info("Deleted connector '$provider' for userId=$userId")
        } catch (e: Exception) {
            application.log.error("Failed to delete connector '$provider' for userId=$userId", e)
            throw e
        }
    }

    suspend fun saveConnectorSession(session: ConnectorSessionInternal) {
        try {
            if (session.id.isBlank()) throw IllegalArgumentException("Connector session id is required")
            firestore.collection(CONNECTOR_SESSIONS_COLLECTION)
                .document(session.id)
                .set(session)
                .await()
            application.log.info("Saved connector session '${session.id}' for userId=${session.userId}")
        } catch (e: Exception) {
            application.log.error("Failed to save connector session '${session.id}' for userId=${session.userId}", e)
            throw e
        }
    }

    suspend fun getConnectorSession(sessionId: String): ConnectorSessionInternal? {
        return try {
            val snapshot = firestore.collection(CONNECTOR_SESSIONS_COLLECTION)
                .document(sessionId)
                .get()
                .await()
            if (!snapshot.exists()) return null
            snapshot.toObject(ConnectorSessionInternal::class.java)
        } catch (e: Exception) {
            application.log.error("Failed to fetch connector session '$sessionId'", e)
            null
        }
    }

    // ==================== Browser Profile + Session Operations ====================

    private fun encryptBrowserSession(session: BrowserSessionInternal): BrowserSessionInternal {
        val aadPrefix = "${session.userId}:${session.id}"
        return session.copy(
            providerSessionId = cryptoService.encrypt(session.providerSessionId, "$aadPrefix:providerSessionId"),
            providerLiveViewUrl = cryptoService.encrypt(session.providerLiveViewUrl, "$aadPrefix:providerLiveViewUrl"),
            providerInteractiveLiveViewUrl = cryptoService.encrypt(session.providerInteractiveLiveViewUrl, "$aadPrefix:providerInteractiveLiveViewUrl"),
            providerCdpUrl = cryptoService.encrypt(session.providerCdpUrl, "$aadPrefix:providerCdpUrl")
        )
    }

    private fun decryptBrowserSession(session: BrowserSessionInternal): BrowserSessionInternal {
        val aadPrefix = "${session.userId}:${session.id}"
        return session.copy(
            providerSessionId = cryptoService.decrypt(session.providerSessionId, "$aadPrefix:providerSessionId"),
            providerLiveViewUrl = cryptoService.decrypt(session.providerLiveViewUrl, "$aadPrefix:providerLiveViewUrl"),
            providerInteractiveLiveViewUrl = cryptoService.decrypt(session.providerInteractiveLiveViewUrl, "$aadPrefix:providerInteractiveLiveViewUrl"),
            providerCdpUrl = cryptoService.decrypt(session.providerCdpUrl, "$aadPrefix:providerCdpUrl")
        )
    }

    suspend fun saveBrowserProfile(userId: String, profile: BrowserProfileInternal) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                .document(profile.id)
                .set(profile)
                .await()
            application.log.info("Saved browser profile '${profile.id}' for userId=$userId")
        } catch (e: Exception) {
            application.log.error("Failed to save browser profile '${profile.id}' for userId=$userId", e)
            throw e
        }
    }

    suspend fun getBrowserProfile(userId: String, profileId: String): BrowserProfileInternal? {
        return try {
            val snapshot = firestore.collection(USERS)
                .document(userId)
                .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                .document(profileId)
                .get()
                .await()
            if (!snapshot.exists()) return null
            snapshot.toObject(BrowserProfileInternal::class.java)
        } catch (e: Exception) {
            application.log.error("Failed to get browser profile '$profileId' for userId=$userId", e)
            null
        }
    }

    suspend fun listBrowserProfiles(userId: String): List<BrowserProfileInternal> {
        return try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(BrowserProfileInternal::class.java) }
                .filterNot { it.status.equals("deleted", ignoreCase = true) }
        } catch (e: Exception) {
            application.log.error("Failed to list browser profiles for userId=$userId", e)
            emptyList()
        }
    }

    suspend fun rotateBrowserProfile(
        userId: String,
        profileId: String,
        newProviderProfileName: String,
        nowIso: String
    ): BrowserProfileInternal? {
        return try {
            val current = getBrowserProfile(userId, profileId) ?: return null
            val rotated = current.copy(
                providerProfileName = newProviderProfileName,
                generation = current.generation + 1,
                lockedBySessionId = null,
                lockedAt = null,
                lockExpiresAt = null,
                lastHeartbeatAt = null,
                lastUsedAt = nowIso,
                retiredProviderProfileNames = (current.retiredProviderProfileNames + current.providerProfileName).distinct()
            )
            saveBrowserProfile(userId, rotated)
            rotated
        } catch (e: Exception) {
            application.log.error("Failed to rotate browser profile '$profileId' for userId=$userId", e)
            throw e
        }
    }

    suspend fun acquireBrowserProfileLockAndCreateSession(
        userId: String,
        profileId: String,
        session: BrowserSessionInternal,
        lockExpiresAtIso: String,
        nowIso: String,
        staleHeartbeatSeconds: Long
    ) {
        try {
            firestore.runTransaction { transaction ->
                val profileRef = firestore.collection(USERS)
                    .document(userId)
                    .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                    .document(profileId)
                val sessionRef = firestore.collection(BROWSER_SESSIONS_COLLECTION).document(session.id)

                val profileSnapshot = transaction.get(profileRef).get()
                val profile = profileSnapshot.toObject(BrowserProfileInternal::class.java)
                    ?: throw IllegalStateException("Browser profile '$profileId' not found")

                val lockedBy = profile.lockedBySessionId
                val lockExpiresAt = profile.lockExpiresAt
                val lastHeartbeatAt = profile.lastHeartbeatAt
                val lockActive = !lockedBy.isNullOrBlank() &&
                    !isIsoTimestampExpired(lockExpiresAt, nowIso) &&
                    !isHeartbeatStale(lastHeartbeatAt, nowIso, staleHeartbeatSeconds)

                if (lockActive && lockedBy != session.id) {
                    throw IllegalStateException("Browser profile '$profileId' is locked")
                }

                transaction.set(sessionRef, session)
                transaction.update(
                    profileRef,
                    mapOf(
                        "lockedBySessionId" to session.id,
                        "lockedAt" to nowIso,
                        "lockExpiresAt" to lockExpiresAtIso,
                        "lastHeartbeatAt" to nowIso,
                        "lastUsedAt" to nowIso
                    )
                )
                null
            }.await()
        } catch (e: Exception) {
            application.log.error("Failed to acquire browser lock for profile '$profileId' userId=$userId", e)
            throw e
        }
    }

    suspend fun saveBrowserSession(session: BrowserSessionInternal) {
        try {
            val encrypted = encryptBrowserSession(session)
            firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .document(session.id)
                .set(encrypted)
                .await()
            application.log.info("Saved browser session '${session.id}' for userId=${session.userId}")
        } catch (e: Exception) {
            application.log.error("Failed to save browser session '${session.id}'", e)
            throw e
        }
    }

    suspend fun getBrowserSessionInternal(sessionId: String): BrowserSessionInternal? {
        return try {
            val snapshot = firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .document(sessionId)
                .get()
                .await()
            if (!snapshot.exists()) return null
            val session = snapshot.toObject(BrowserSessionInternal::class.java) ?: return null
            decryptBrowserSession(session)
        } catch (e: CryptoOperationException) {
            application.log.error("Failed to decrypt browser session '$sessionId'", e)
            throw e
        } catch (e: Exception) {
            application.log.error("Failed to fetch browser session '$sessionId'", e)
            null
        }
    }

    suspend fun findLatestBrowserSessionByTaskId(
        userId: String,
        taskId: String,
        states: Collection<String> = emptyList()
    ): BrowserSessionInternal? {
        return try {
            firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .whereEqualTo("userId", userId)
                .whereEqualTo("taskId", taskId)
                .get()
                .await()
                .documents
                .mapNotNull { doc ->
                    val session = doc.toObject(BrowserSessionInternal::class.java)
                    runCatching { decryptBrowserSession(session) }
                        .onFailure { application.log.error("Failed to decrypt browser session ${doc.id}", it) }
                        .getOrNull()
                }
                .filter { states.isEmpty() || it.state in states }
                .maxByOrNull { it.updatedAt.ifBlank { it.createdAt } }
        } catch (e: Exception) {
            application.log.error("Failed to find browser session for userId=$userId taskId=$taskId", e)
            null
        }
    }

    suspend fun listBrowserSessionsByStates(states: Collection<String>): List<BrowserSessionInternal> {
        if (states.isEmpty()) return emptyList()
        return try {
            firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .whereIn("state", states.toList())
                .get()
                .await()
                .documents
                .mapNotNull { doc ->
                    val session = doc.toObject(BrowserSessionInternal::class.java)
                    runCatching { decryptBrowserSession(session) }
                        .onFailure { application.log.error("Failed to decrypt browser session ${doc.id}", it) }
                        .getOrNull()
                }
        } catch (e: Exception) {
            application.log.error("Failed to list browser sessions by states=${states.joinToString()}", e)
            emptyList()
        }
    }

    suspend fun countActiveBrowserSessions(userId: String? = null): Int {
        return try {
            val query = firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .whereIn("state", BrowserSessionState.liveStates.toList())
                .let { if (userId == null) it else it.whereEqualTo("userId", userId) }
            query.get().await().documents.size
        } catch (e: Exception) {
            application.log.error("Failed to count active browser sessions for userId=$userId", e)
            0
        }
    }

    suspend fun releaseBrowserProfileLock(userId: String, profileId: String, ownedBySessionId: String? = null) {
        try {
            val profileRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                .document(profileId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(profileRef).get()
                if (!snapshot.exists()) return@runTransaction null
                val profile = snapshot.toObject(BrowserProfileInternal::class.java) ?: return@runTransaction null
                if (!ownedBySessionId.isNullOrBlank() && profile.lockedBySessionId != ownedBySessionId) return@runTransaction null
                transaction.update(
                    profileRef,
                    mapOf(
                        "lockedBySessionId" to null,
                        "lockedAt" to null,
                        "lockExpiresAt" to null
                    )
                )
                null
            }.await()
        } catch (e: Exception) {
            application.log.error("Failed to release browser profile lock '$profileId' userId=$userId", e)
            throw e
        }
    }

    suspend fun heartbeatBrowserSession(sessionId: String, nowIso: String) {
        try {
            firestore.collection(BROWSER_SESSIONS_COLLECTION)
                .document(sessionId)
                .set(mapOf("lastHeartbeatAt" to nowIso, "updatedAt" to nowIso), SetOptions.merge())
                .await()
        } catch (e: Exception) {
            application.log.error("Failed to heartbeat browser session '$sessionId'", e)
        }
    }

    suspend fun heartbeatBrowserProfile(userId: String, profileId: String, nowIso: String) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .collection(USER_BROWSER_PROFILES_SUBCOLLECTION)
                .document(profileId)
                .set(mapOf("lastHeartbeatAt" to nowIso, "lastUsedAt" to nowIso), SetOptions.merge())
                .await()
        } catch (e: Exception) {
            application.log.error("Failed to heartbeat browser profile '$profileId' userId=$userId", e)
        }
    }

    private fun isIsoTimestampExpired(isoValue: String?, nowIso: String): Boolean {
        if (isoValue.isNullOrBlank()) return true
        val now = runCatching { java.time.Instant.parse(nowIso) }.getOrNull() ?: return true
        val expiresAt = runCatching { java.time.Instant.parse(isoValue) }.getOrNull() ?: return true
        return !expiresAt.isAfter(now)
    }

    private fun isHeartbeatStale(lastHeartbeatAt: String?, nowIso: String, staleHeartbeatSeconds: Long): Boolean {
        if (lastHeartbeatAt.isNullOrBlank()) return true
        val now = runCatching { java.time.Instant.parse(nowIso) }.getOrNull() ?: return true
        val heartbeat = runCatching { java.time.Instant.parse(lastHeartbeatAt) }.getOrNull() ?: return true
        return heartbeat.plusSeconds(staleHeartbeatSeconds).isBefore(now)
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

            deleteChatThreads(userId)

            application.log.info("User droplet deleted successfully for user: $userId")
        } catch (e: Exception) {
            application.log.error("Failed to delete user droplet for user $userId", e)
        }
    }

    // ==================== Chat History Operations ====================

    suspend fun upsertChatMessage(write: ChatMessageWrite) {
        try {
            val userRef = firestore.collection(USERS).document(write.userId)
            val threadRef = userRef.collection(USER_CHAT_THREADS_SUBCOLLECTION).document(write.threadId)
            val messageRef = threadRef.collection(CHAT_MESSAGES_SUBCOLLECTION).document(write.id)

            val encryptedContent = ChatHistoryCrypto.encryptContent(
                cryptoService = cryptoService,
                userId = write.userId,
                threadId = write.threadId,
                messageId = write.id,
                value = write.content
            )
            val encryptedRaw = ChatHistoryCrypto.encryptRawFrame(
                cryptoService = cryptoService,
                userId = write.userId,
                threadId = write.threadId,
                messageId = write.id,
                value = write.rawFrameJson
            )
            val encryptedPreview = ChatHistoryCrypto.encryptPreview(
                cryptoService = cryptoService,
                userId = write.userId,
                threadId = write.threadId,
                value = write.content
            )

            firestore.runTransaction { transaction ->
                val existingMessage = transaction.get(messageRef).get()
                val existingThread = transaction.get(threadRef).get()

                val persistedCreatedAt = existingMessage.getString("createdAt") ?: write.createdAt
                val threadCreatedAt = existingThread.getString("createdAt") ?: write.createdAt
                val existingCount = existingThread.getLong("messageCount") ?: 0L
                val currentUpdatedAt = existingThread.getString("updatedAt")
                val currentLastMessageAt = existingThread.getString("lastMessageAt")

                val messageData = mutableMapOf<String, Any>(
                    "id" to write.id,
                    "threadId" to write.threadId,
                    "threadKey" to write.threadKey,
                    "role" to write.role,
                    "kind" to write.kind,
                    "direction" to write.direction,
                    "complete" to write.complete,
                    "createdAt" to persistedCreatedAt,
                    "updatedAt" to write.updatedAt,
                    "content" to encryptedContent,
                    "rawFrameJson" to encryptedRaw
                )
                write.frameId?.let { messageData["frameId"] = it }
                write.state?.let { messageData["state"] = it }
                write.taskId?.let { messageData["taskId"] = it }
                write.agentId?.let { messageData["agentId"] = it }
                transaction.set(messageRef, messageData, SetOptions.merge())

                val threadData = mutableMapOf<String, Any>(
                    "threadId" to write.threadId,
                    "threadKey" to write.threadKey,
                    "createdAt" to threadCreatedAt,
                    "updatedAt" to maxOf(currentUpdatedAt ?: write.updatedAt, write.updatedAt),
                    "messageCount" to if (existingMessage.exists()) existingCount else existingCount + 1L
                )
                write.taskId?.let { threadData["taskId"] = it }
                write.agentId?.let { threadData["agentId"] = it }
                if (currentLastMessageAt == null || currentLastMessageAt <= write.updatedAt) {
                    threadData["lastMessageAt"] = write.updatedAt
                    threadData["lastRole"] = write.role
                    threadData["preview"] = encryptedPreview
                }
                transaction.set(threadRef, threadData, SetOptions.merge())
                null
            }.await()
        } catch (e: Exception) {
            application.log.error(
                "Failed to upsert chat history message ${write.id} for user ${write.userId}",
                e
            )
            throw e
        }
    }

    suspend fun listChatThreads(userId: String, limit: Int, after: ChatThreadCursor? = null): List<ChatThreadView> {
        return try {
            var query = firestore.collection(USERS)
                .document(userId)
                .collection(USER_CHAT_THREADS_SUBCOLLECTION)
                .orderBy("lastMessageAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .orderBy("threadId", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .limit(limit)

            if (after != null) {
                query = query.startAfter(after.lastMessageAt, after.threadId)
            }

            query.get().await().documents.mapNotNull { doc ->
                runCatching { decodeChatThread(userId, doc) }
                    .onFailure { application.log.error("Failed to decode chat thread ${doc.id} for user $userId", it) }
                    .getOrNull()
            }
        } catch (e: Exception) {
            application.log.error("Failed to list chat threads for user $userId", e)
            emptyList()
        }
    }

    suspend fun listChatMessages(
        userId: String,
        threadId: String,
        limit: Int,
        after: ChatMessageCursor? = null
    ): List<ChatMessageView> {
        return try {
            val threadRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_CHAT_THREADS_SUBCOLLECTION)
                .document(threadId)

            var query = threadRef.collection(CHAT_MESSAGES_SUBCOLLECTION)
                .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.ASCENDING)
                .orderBy("id", com.google.cloud.firestore.Query.Direction.ASCENDING)
                .limit(limit)

            if (after != null) {
                query = query.startAfter(after.createdAt, after.messageId)
            }

            query.get().await().documents.mapNotNull { doc ->
                runCatching { decodeChatMessage(userId, threadId, doc) }
                    .onFailure {
                        application.log.error(
                            "Failed to decode chat message ${doc.id} for user $userId thread $threadId",
                            it
                        )
                    }
                    .getOrNull()
            }
        } catch (e: Exception) {
            application.log.error("Failed to list chat messages for user $userId thread $threadId", e)
            emptyList()
        }
    }

    private fun decodeChatThread(userId: String, doc: DocumentSnapshot): ChatThreadView {
        val threadId = doc.getString("threadId") ?: doc.id
        val previewEncrypted = doc.getString("preview").orEmpty()
        val preview = if (previewEncrypted.isBlank()) {
            ""
        } else {
            ChatHistoryCrypto.decryptPreview(cryptoService, userId, threadId, previewEncrypted)
        }

        return ChatThreadView(
            threadId = threadId,
            threadKey = doc.getString("threadKey").orEmpty(),
            taskId = doc.getString("taskId"),
            agentId = doc.getString("agentId"),
            createdAt = doc.getString("createdAt").orEmpty(),
            updatedAt = doc.getString("updatedAt").orEmpty(),
            lastMessageAt = doc.getString("lastMessageAt").orEmpty(),
            lastRole = doc.getString("lastRole").orEmpty(),
            messageCount = doc.getLong("messageCount") ?: 0L,
            preview = preview
        )
    }

    private fun decodeChatMessage(userId: String, threadId: String, doc: DocumentSnapshot): ChatMessageView {
        val messageId = doc.getString("id") ?: doc.id
        val content = ChatHistoryCrypto.decryptContent(
            cryptoService = cryptoService,
            userId = userId,
            threadId = threadId,
            messageId = messageId,
            value = doc.getString("content").orEmpty()
        )

        return ChatMessageView(
            id = messageId,
            threadId = threadId,
            threadKey = doc.getString("threadKey").orEmpty(),
            role = doc.getString("role").orEmpty(),
            kind = doc.getString("kind").orEmpty(),
            direction = doc.getString("direction").orEmpty(),
            frameId = doc.getString("frameId"),
            state = doc.getString("state"),
            complete = doc.getBoolean("complete") ?: true,
            taskId = doc.getString("taskId"),
            agentId = doc.getString("agentId"),
            createdAt = doc.getString("createdAt").orEmpty(),
            updatedAt = doc.getString("updatedAt").orEmpty(),
            content = content
        )
    }

    private suspend fun deleteChatThreads(userId: String) {
        val threadDocs = firestore.collection(USERS)
            .document(userId)
            .collection(USER_CHAT_THREADS_SUBCOLLECTION)
            .get()
            .await()
            .documents

        threadDocs.forEach { threadDoc ->
            val messageDocs = threadDoc.reference.collection(CHAT_MESSAGES_SUBCOLLECTION).get().await().documents
            deleteDocuments(messageDocs.map { it.reference })
        }
        deleteDocuments(threadDocs.map { it.reference })
    }

    private suspend fun deleteDocuments(refs: List<com.google.cloud.firestore.DocumentReference>) {
        refs.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
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

    // ==================== FCM Token Operations ====================

    suspend fun saveFcmToken(userId: String, token: String) {
        try {
            firestore.collection(USERS)
                .document(userId)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
            application.log.info("FCM token saved for userId=$userId")
        } catch (e: Exception) {
            application.log.error("Failed to save FCM token for userId=$userId", e)
            throw e
        }
    }

    suspend fun getFcmToken(userId: String): String? {
        return try {
            firestore.collection(USERS)
                .document(userId)
                .get()
                .await()
                .getString("fcmToken")
        } catch (e: Exception) {
            application.log.error("Failed to get FCM token for userId=$userId", e)
            null
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
        } catch (e: CryptoOperationException) {
            application.log.error("Failed to decrypt project ref mapping for $projectRef", e)
            throw e
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

    suspend fun getDailyTokenUsage(userId: String, dayUtc: String): Long {
        return try {
            val docRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_USAGE_SUBCOLLECTION)
                .document(dayUtc)

            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                snapshot.getLong("totalTokens") ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch daily token usage for user $userId day $dayUtc", e)
            0L
        }
    }

    /**
     * Retrieves daily credit usage for a specific user and day.
     * Credits are weighted: input=1x, output=2x, 1000 tokens = 1 credit
     */
    suspend fun getDailyCreditUsage(userId: String, dayUtc: String): Long {
        return try {
            val docRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_USAGE_SUBCOLLECTION)
                .document(dayUtc)

            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                snapshot.getLong("credits") ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch daily credit usage for user $userId day $dayUtc", e)
            0L
        }
    }

    /**
     * Retrieves detailed daily usage data for a specific user and day.
     * Returns null if no usage data exists for that day.
     */
    suspend fun getDailyUsageDetail(userId: String, dayUtc: String): DailyUsageData? {
        return try {
            val docRef = firestore.collection(USERS)
                .document(userId)
                .collection(USER_USAGE_SUBCOLLECTION)
                .document(dayUtc)

            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                DailyUsageData(
                    userId = snapshot.getString("userId") ?: userId,
                    dayUtc = snapshot.getString("dayUtc") ?: dayUtc,
                    promptTokens = snapshot.getLong("promptTokens") ?: 0L,
                    completionTokens = snapshot.getLong("completionTokens") ?: 0L,
                    totalTokens = snapshot.getLong("totalTokens") ?: 0L,
                    inboundPromptTokens = snapshot.getLong("inboundPromptTokens") ?: 0L,
                    inboundCompletionTokens = snapshot.getLong("inboundCompletionTokens") ?: 0L,
                    inboundTotalTokens = snapshot.getLong("inboundTotalTokens") ?: 0L,
                    outboundPromptTokens = snapshot.getLong("outboundPromptTokens") ?: 0L,
                    outboundCompletionTokens = snapshot.getLong("outboundCompletionTokens") ?: 0L,
                    outboundTotalTokens = snapshot.getLong("outboundTotalTokens") ?: 0L,
                    usageEvents = snapshot.getLong("usageEvents") ?: 0L,
                    lastSessionId = snapshot.getString("lastSessionId") ?: "",
                    credits = snapshot.getLong("credits") ?: 0L
                )
            } else {
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch daily usage detail for user $userId day $dayUtc", e)
            null
        }
    }

    suspend fun incrementUserTokenUsageDaily(
        userId: String,
        dayUtc: String,
        delta: TokenUsageDelta,
        sessionId: String
    ) {
        if (delta.isZero()) return

        // Calculate credits: (input * 1 + output * 2) / 1000
        val credits = CreditCalculator.toCredits(delta)

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
                        "usageEvents" to FieldValue.increment(delta.usageEvents),
                        "credits" to FieldValue.increment(credits)
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

    // ==================== Host Management Operations (Podman Multi-Tenant) ====================

    /**
     * Saves or updates host information for Podman multi-tenant architecture.
     */
    suspend fun saveHostInfo(hostInfo: com.suprbeta.podman.models.HostInfo) {
        try {
            application.log.info("Saving host info: ${hostInfo.hostId}")
            firestore.collection(HOSTS_COLLECTION)
                .document(hostInfo.hostId.toString())
                .set(hostInfo)
                .await()
            application.log.info("✅ Host info saved: ${hostInfo.hostId}")
        } catch (e: Exception) {
            application.log.error("Failed to save host info: ${hostInfo.hostId}", e)
            throw e
        }
    }

    /**
     * Retrieves host information by host ID.
     */
    suspend fun getHostInfo(hostId: Long): com.suprbeta.podman.models.HostInfo? {
        return try {
            application.log.debug("Fetching host info: $hostId")
            val snapshot = firestore.collection(HOSTS_COLLECTION)
                .document(hostId.toString())
                .get()
                .await()
            
            if (snapshot.exists()) {
                mapHostInfo(snapshot)
            } else {
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch host info: $hostId", e)
            null
        }
    }

    /**
     * Lists all hosts.
     */
    suspend fun listHosts(): List<com.suprbeta.podman.models.HostInfo> {
        return try {
            application.log.debug("Fetching all hosts")
            val snapshot = firestore.collection(HOSTS_COLLECTION).get().await()
            snapshot.documents.mapNotNull(::mapHostInfo)
        } catch (e: Exception) {
            application.log.error("Failed to fetch hosts", e)
            emptyList()
        }
    }

    /**
     * Atomically increments container count on a host and updates its status.
     * Uses a Firestore transaction to avoid the read-modify-write race condition.
     */
    suspend fun incrementHostContainerCount(hostId: Long, totalCapacity: Int) {
        try {
            val docRef = firestore.collection(HOSTS_COLLECTION).document(hostId.toString())
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef).get()
                val newCount = (snapshot.getLong("currentContainers") ?: 0L) + 1
                val newStatus = if (newCount >= totalCapacity) HostInfo.STATUS_FULL else HostInfo.STATUS_ACTIVE
                transaction.update(docRef, mapOf("currentContainers" to newCount, "status" to newStatus))
            }.await()
        } catch (e: Exception) {
            application.log.error("Failed to increment container count for host $hostId", e)
            throw e
        }
    }

    /**
     * Atomically decrements container count on a host and marks it active.
     * Uses a Firestore transaction to avoid the read-modify-write race condition.
     */
    suspend fun decrementHostContainerCount(hostId: Long) {
        try {
            val docRef = firestore.collection(HOSTS_COLLECTION).document(hostId.toString())
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef).get()
                val newCount = ((snapshot.getLong("currentContainers") ?: 1L) - 1).coerceAtLeast(0)
                transaction.update(docRef, mapOf("currentContainers" to newCount, "status" to HostInfo.STATUS_ACTIVE))
            }.await()
        } catch (e: Exception) {
            application.log.error("Failed to decrement container count for host $hostId", e)
            throw e
        }
    }

    /**
     * Saves user-to-host mapping.
     */
    suspend fun saveUserHostMapping(userId: String, hostId: Long) {
        try {
            application.log.info("Saving user-host mapping: $userId -> $hostId")
            firestore.collection(USER_HOST_MAPPINGS_COLLECTION)
                .document(userId)
                .set(mapOf("hostId" to hostId, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
        } catch (e: Exception) {
            application.log.error("Failed to save user-host mapping: $userId -> $hostId", e)
            throw e
        }
    }

    /**
     * Retrieves host ID for a user.
     */
    suspend fun getUserHostMapping(userId: String): Long? {
        return try {
            application.log.debug("Fetching host mapping for user: $userId")
            val snapshot = firestore.collection(USER_HOST_MAPPINGS_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            if (snapshot.exists()) {
                snapshot.getLong("hostId")
            } else {
                null
            }
        } catch (e: Exception) {
            application.log.error("Failed to fetch user-host mapping: $userId", e)
            null
        }
    }

    /**
     * Deletes user-to-host mapping.
     */
    suspend fun deleteUserHostMapping(userId: String) {
        try {
            application.log.info("Deleting user-host mapping: $userId")
            firestore.collection(USER_HOST_MAPPINGS_COLLECTION)
                .document(userId)
                .delete()
                .await()
        } catch (e: Exception) {
            application.log.error("Failed to delete user-host mapping: $userId", e)
        }
    }

    // ==================== Admin Operations ====================

    /**
     * Lists all known user IDs from the top-level users collection.
     */
    suspend fun listUserIds(): List<String> {
        return try {
            val snapshot = firestore.collection(USERS).get().await()
            snapshot.documents.map { it.id }
        } catch (e: Exception) {
            application.log.error("Failed to list user IDs", e)
            emptyList()
        }
    }

    /**
     * Lists all user droplet documents across every user.
     *
     * This reads raw internal droplet documents without decrypting fields so it can
     * be used for admin inventory views that only need non-secret metadata.
     */
    suspend fun listAllUserDropletsInternal(): List<UserDropletInternal> {
        return try {
            val snapshot = firestore.collectionGroup(USER_DROPLETS_SUBCOLLECTION).get().await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    mapUserDropletInternal(doc)
                } catch (e: Exception) {
                    application.log.warn("Failed to deserialize user droplet document ${doc.reference.path}", e)
                    null
                }
            }
        } catch (e: Exception) {
            application.log.error("Failed to list all user droplets", e)
            emptyList()
        }
    }

    suspend fun getUserDropletInternalByGatewayToken(gatewayToken: String): UserDropletInternal? {
        if (gatewayToken.isBlank()) return null
        return try {
            val snapshot = firestore.collectionGroup(USER_DROPLETS_SUBCOLLECTION).get().await()
            snapshot.documents.firstNotNullOfOrNull { doc ->
                val raw = runCatching { mapUserDropletInternal(doc) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (raw.gatewayToken == gatewayToken) {
                    runCatching { saveUserDroplet(raw) }
                        .onFailure { application.log.warn("Failed to migrate plaintext gateway token for userId=${raw.userId}", it) }
                    return@firstNotNullOfOrNull raw
                }
                val decrypted = runCatching { decryptDroplet(raw) }
                    .onFailure { application.log.warn("Failed to decrypt droplet during gateway token lookup: ${doc.reference.path}", it) }
                    .getOrNull()
                decrypted?.takeIf { it.gatewayToken == gatewayToken }
            }
        } catch (e: Exception) {
            application.log.error("Failed to lookup user droplet by gateway token", e)
            null
        }
    }
}
