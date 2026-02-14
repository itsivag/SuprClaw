package com.suprbeta.firebase

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*

/**
 * Ktor plugin for Firebase Authentication
 *
 * Provides HTTP route protection with Bearer token authentication
 */
class FirebaseAuthPlugin(val authService: FirebaseAuthService) {

    class Configuration {
        var authService: FirebaseAuthService? = null
    }

    companion object Plugin : BaseApplicationPlugin<Application, Configuration, FirebaseAuthPlugin> {
        override val key = AttributeKey<FirebaseAuthPlugin>("FirebaseAuth")

        override fun install(
            pipeline: Application,
            configure: Configuration.() -> Unit
        ): FirebaseAuthPlugin {
            val config = Configuration().apply(configure)
            val authService = config.authService
                ?: error("FirebaseAuthService must be provided to FirebaseAuthPlugin")
            return FirebaseAuthPlugin(authService)
        }
    }
}

/**
 * Attribute key for storing verified Firebase user in call attributes
 */
val firebaseUserKey = AttributeKey<FirebaseUser>("FirebaseUser")

/**
 * Route extension function for protecting endpoints with Firebase authentication
 *
 * Usage:
 * ```
 * routing {
 *     authenticated {
 *         post("/api/droplets") {
 *             val user = call.attributes[firebaseUserKey]
 *             // ... handle request
 *         }
 *     }
 * }
 * ```
 */
fun Route.authenticated(build: Route.() -> Unit): Route {
    // Create authenticated route interceptor
    intercept(ApplicationCallPipeline.Call) {
        val authHeader = call.request.headers["Authorization"]

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            application.log.warn("Authentication failed: Missing or invalid Authorization header")
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Missing or invalid Authorization header")
            )
            finish()
            return@intercept
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        val authService = application.plugin(FirebaseAuthPlugin).authService
        val user = authService.verifyToken(token)

        if (user == null) {
            application.log.warn("Authentication failed: Invalid or expired token")
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Invalid or expired token")
            )
            finish()
            return@intercept
        }

        application.log.debug("Authentication successful for user: ${user.uid}")
        call.attributes.put(firebaseUserKey, user)
    }

    // Apply the build block to set up child routes
    build()

    return this
}
