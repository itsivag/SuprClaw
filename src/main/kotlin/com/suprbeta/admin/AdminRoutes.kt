package com.suprbeta.admin

import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.digitalocean.DropletProvisioningService
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.authenticated
import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAdminRoutes(
    firestoreRepository: FirestoreRepository,
    provisioningService: DropletProvisioningService,
    metricsService: AdminMetricsService = AdminMetricsService(
        firestoreRepository = firestoreRepository,
        sshCommandExecutor = SshCommandExecutorImpl(this),
        application = this
    )
) {
    val adminUserService = AdminUserService(firestoreRepository)
    val firebaseConfig = loadFirebaseWebConfig()
    val adminPageHtml = loadAdminHtml()

    routing {
        get("/admin") {
            call.respondText(adminPageHtml, ContentType.Text.Html, HttpStatusCode.OK)
        }

        get("/api/admin/config") {
            call.respond(HttpStatusCode.OK, firebaseConfig)
        }

        authenticated {
            adminOnly {
                route("/api/admin") {
                    get("/users") {
                        val scope = AdminUserScope.fromQuery(call.request.queryParameters["scope"])
                        val response = adminUserService.listUsers(scope)
                        call.respond(HttpStatusCode.OK, response)
                    }

                    get("/metrics") {
                        try {
                            val response = metricsService.collectMetrics()
                            call.respond(HttpStatusCode.OK, response)
                        } catch (e: Exception) {
                            call.application.log.error("Failed to collect admin metrics", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to "Failed to collect metrics")
                            )
                        }
                    }

                    delete("/users/{userId}") {
                        val userId = call.parameters["userId"]?.trim()
                        if (userId.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
                            return@delete
                        }

                        try {
                            val droplet = firestoreRepository.getUserDropletInternal(userId)
                            if (droplet == null) {
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf("error" to "User has no provisioned container to delete")
                                )
                                return@delete
                            }

                            provisioningService.teardown(userId)
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf("message" to "Provisioned container deleted for user $userId")
                            )
                        } catch (e: IllegalStateException) {
                            call.respond(
                                HttpStatusCode.Conflict,
                                mapOf("error" to (e.message ?: "User teardown failed"))
                            )
                        } catch (e: Exception) {
                            call.application.log.error("Admin teardown failed for user $userId", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to "Failed to delete provisioned container")
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Application.loadAdminHtml(): String {
    val resourcePath = "admin/index.html"
    val stream = environment.classLoader.getResourceAsStream(resourcePath)
        ?: throw IllegalStateException("Missing admin page resource: $resourcePath")
    return stream.bufferedReader().use { it.readText() }
}

private fun loadFirebaseWebConfig(): AdminFirebaseConfigResponse {
    val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    fun env(key: String): String = dotenv[key] ?: System.getenv(key) ?: ""

    val apiKey = env("FIREBASE_WEB_API_KEY")
    val projectId = env("FIREBASE_PROJECT_ID")
    val appId = env("FIREBASE_WEB_APP_ID")
    val authDomain = env("FIREBASE_WEB_AUTH_DOMAIN").ifBlank {
        projectId.takeIf { it.isNotBlank() }?.let { "$it.firebaseapp.com" } ?: ""
    }
    val messagingSenderId = env("FIREBASE_WEB_MESSAGING_SENDER_ID").ifBlank { null }
    val storageBucket = env("FIREBASE_WEB_STORAGE_BUCKET").ifBlank { null }
    val measurementId = env("FIREBASE_WEB_MEASUREMENT_ID").ifBlank { null }

    val missing = buildList {
        if (apiKey.isBlank()) add("FIREBASE_WEB_API_KEY")
        if (projectId.isBlank()) add("FIREBASE_PROJECT_ID")
        if (appId.isBlank()) add("FIREBASE_WEB_APP_ID")
        if (authDomain.isBlank()) add("FIREBASE_WEB_AUTH_DOMAIN")
    }

    return AdminFirebaseConfigResponse(
        configured = missing.isEmpty(),
        missing = missing,
        apiKey = apiKey,
        authDomain = authDomain,
        projectId = projectId,
        appId = appId,
        messagingSenderId = messagingSenderId,
        storageBucket = storageBucket,
        measurementId = measurementId
    )
}
