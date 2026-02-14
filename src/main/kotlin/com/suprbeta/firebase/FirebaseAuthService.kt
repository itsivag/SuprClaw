package com.suprbeta.firebase

import com.google.api.core.ApiFuture
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extension function to convert Firebase ApiFuture to suspend function
 */
private suspend fun <T> ApiFuture<T>.await(): T = withContext(Dispatchers.IO) {
    get()
}

/**
 * Data class representing a verified Firebase user
 */
data class FirebaseUser(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val provider: String? = null,  // "google.com" or "apple.com" for SSO
    val customClaims: Map<String, Any> = emptyMap()
)

/**
 * Service for Firebase Authentication token verification
 */
class FirebaseAuthService(
    private val firebaseService: FirebaseService,
    private val application: Application
) {
    private val firebaseAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    /**
     * Verifies a Firebase ID token and returns user information
     * @param idToken The Firebase ID token to verify
     * @return FirebaseUser if valid, null if invalid or expired
     */
    suspend fun verifyToken(idToken: String): FirebaseUser? {
        return try {
            application.log.debug("Verifying Firebase ID token")

            val decodedToken: FirebaseToken = firebaseAuth
                .verifyIdTokenAsync(idToken)
                .await()

            // Extract primary provider (Google or Apple SSO) from claims
            val firebaseClaims = decodedToken.claims["firebase"] as? Map<*, *>
            val provider = firebaseClaims?.get("sign_in_provider") as? String

            val user = FirebaseUser(
                uid = decodedToken.uid,
                email = decodedToken.email,
                emailVerified = decodedToken.isEmailVerified,
                provider = provider,
                customClaims = decodedToken.claims
            )

            application.log.debug("Token verified successfully for user: ${user.uid}")
            user
        } catch (e: FirebaseAuthException) {
            application.log.warn("Firebase auth token verification failed: ${e.message}")
            null
        } catch (e: Exception) {
            application.log.error("Unexpected error during token verification", e)
            null
        }
    }

    /**
     * Verifies a Firebase ID token and throws exception if invalid
     * @param idToken The Firebase ID token to verify
     * @return FirebaseUser if valid
     * @throws IllegalArgumentException if token is invalid or expired
     */
    suspend fun verifyTokenOrThrow(idToken: String): FirebaseUser {
        return verifyToken(idToken)
            ?: throw IllegalArgumentException("Invalid or expired Firebase ID token")
    }
}
