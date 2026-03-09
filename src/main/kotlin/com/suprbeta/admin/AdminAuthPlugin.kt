package com.suprbeta.admin

import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.util.*

private const val REQUIRED_ADMIN_ROLE = "admin"

val AdminRoleGuard = createRouteScopedPlugin("AdminRoleGuard") {
    onCall { call ->
        val user = call.attributes.getOrNull(firebaseUserKey)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            return@onCall
        }

        val role = (user.customClaims["role"] as? String)?.trim()?.lowercase()
        if (role != REQUIRED_ADMIN_ROLE) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin access required"))
            return@onCall
        }
    }
}

fun Route.adminOnly(build: Route.() -> Unit): Route {
    val adminRoute = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            io.ktor.server.routing.RouteSelectorEvaluation.Transparent

        override fun toString() = "(adminOnly)"
    })

    adminRoute.install(AdminRoleGuard)
    adminRoute.build()
    return adminRoute
}
