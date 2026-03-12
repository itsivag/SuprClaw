package com.suprbeta.browser

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureBrowserRoutes(browserService: BrowserService) {
    routing {
        authenticated {
            route("/api/browser") {
                route("/profiles") {
                    get {
                        val user = call.attributes[firebaseUserKey]
                        call.respond(HttpStatusCode.OK, browserService.listProfiles(user.uid))
                    }
                    post {
                        val user = call.attributes[firebaseUserKey]
                        val request = runCatching { call.receive<CreateBrowserProfileRequest>() }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid browser profile payload"))
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.Created, browserService.createProfile(user.uid, request))
                        }
                    }
                    delete("/{profileId}") {
                        val user = call.attributes[firebaseUserKey]
                        val profileId = call.parameters["profileId"].orEmpty()
                        if (profileId.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "profileId is required"))
                            return@delete
                        }
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.resetProfile(user.uid, profileId))
                        }
                    }
                }

                route("/sessions") {
                    post {
                        val user = call.attributes[firebaseUserKey]
                        val request = runCatching { call.receive<CreateBrowserSessionRequest>() }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid browser session payload"))
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.Created, browserService.createSession(user.uid, request))
                        }
                    }

                    get("/by-task/{taskId}") {
                        val user = call.attributes[firebaseUserKey]
                        val taskId = call.parameters["taskId"].orEmpty()
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.getSessionByTaskId(user.uid, taskId))
                        }
                    }

                    get("/{sessionId}") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.getSession(user.uid, sessionId))
                        }
                    }

                    delete("/{sessionId}") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.closeSession(user.uid, sessionId))
                        }
                    }

                    post("/{sessionId}/takeover-request") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        val request = runCatching { call.receive<TakeoverRequest>() }.getOrDefault(TakeoverRequest())
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.requestTakeover(user.uid, sessionId, request))
                        }
                    }

                    post("/{sessionId}/resume") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        val request = runCatching { call.receive<ResumeBrowserSessionRequest>() }.getOrDefault(ResumeBrowserSessionRequest())
                        call.respondBrowserErrors {
                            call.respond(HttpStatusCode.OK, browserService.resumeSession(user.uid, sessionId, request))
                        }
                    }

                    get("/{sessionId}/view") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.applyBrowserViewerHeaders()
                            call.respondText(
                                text = browserService.getViewerPage(user.uid, sessionId, interactive = false),
                                contentType = ContentType.Text.Html,
                                status = HttpStatusCode.OK
                            )
                        }
                    }

                    get("/{sessionId}/takeover") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.applyBrowserViewerHeaders()
                            call.respondText(
                                text = browserService.getViewerPage(user.uid, sessionId, interactive = true),
                                contentType = ContentType.Text.Html,
                                status = HttpStatusCode.OK
                            )
                        }
                    }

                    get("/{sessionId}/view/launch") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.applyBrowserLaunchHeaders()
                            call.respondRedirect(browserService.getViewerLaunchUrl(user.uid, sessionId, interactive = false), permanent = false)
                        }
                    }

                    get("/{sessionId}/takeover/launch") {
                        val user = call.attributes[firebaseUserKey]
                        val sessionId = call.parameters["sessionId"].orEmpty()
                        call.respondBrowserErrors {
                            call.applyBrowserLaunchHeaders()
                            call.respondRedirect(browserService.getViewerLaunchUrl(user.uid, sessionId, interactive = true), permanent = false)
                        }
                    }
                }
            }
        }
    }
}

private suspend inline fun io.ktor.server.application.ApplicationCall.respondBrowserErrors(
    crossinline block: suspend () -> Unit
) {
    try {
        block()
    } catch (e: BrowserNotFoundException) {
        application.log.warn("Browser route not found: ${e.message}")
        respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Not found")))
    } catch (e: BrowserValidationException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
    } catch (e: BrowserConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Browser conflict")))
    } catch (e: BrowserCapacityException) {
        respond(
            HttpStatusCode.TooManyRequests,
            mapOf(
                "error" to (e.message ?: "Browser capacity blocked"),
                "code" to BrowserSessionState.CAPACITY_BLOCKED,
                "retryAfterSeconds" to e.retryAfterSeconds
            )
        )
    } catch (e: BrowserFeatureDisabledException) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Cloud browser is disabled"))
    } catch (e: BrowserProviderUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Browser provider unavailable")))
    } catch (e: BrowserProviderException) {
        application.log.error("Browser provider failure", e)
        respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "Browser provider failure")))
    }
}

private fun io.ktor.server.application.ApplicationCall.applyBrowserViewerHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; connect-src 'none'; script-src 'none'; frame-src 'self' https://liveview.firecrawl.dev https://browser.firecrawl.dev;"
    )
}

private fun io.ktor.server.application.ApplicationCall.applyBrowserLaunchHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
}
