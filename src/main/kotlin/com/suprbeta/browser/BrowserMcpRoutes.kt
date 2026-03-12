package com.suprbeta.browser

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Writer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val MCP_SERVER_NAME = "cloud_browser"
private const val SSE_KEEPALIVE_SECONDS = 15L

private data class BrowserMcpSseSession(
    val userId: String,
    val messages: Channel<String>
)

private sealed interface ProcessMcpRequestResult {
    data class Response(val payload: JsonObject) : ProcessMcpRequestResult
    data object Accepted : ProcessMcpRequestResult
    data object AlreadyResponded : ProcessMcpRequestResult
}

fun Application.configureBrowserMcpRoutes(
    browserService: BrowserService,
    firestoreRepository: FirestoreRepository
) {
    val json = Json { ignoreUnknownKeys = true }
    val sseSessions = ConcurrentHashMap<String, BrowserMcpSseSession>()

    routing {
        route("/api/mcp/cloud-browser") {
            get {
                val droplet = call.requireGatewayDroplet(firestoreRepository) ?: return@get
                val sessionId = UUID.randomUUID().toString().replace("-", "")
                val messageChannel = Channel<String>(capacity = Channel.BUFFERED)
                sseSessions[sessionId] = BrowserMcpSseSession(
                    userId = droplet.userId,
                    messages = messageChannel
                )

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                call.response.headers.append("X-Accel-Buffering", "no")

                val messageEndpoint = call.mcpMessageEndpoint(sessionId)
                call.respondTextWriter(
                    contentType = ContentType.Text.EventStream,
                    status = HttpStatusCode.OK
                ) {
                    try {
                        writeSseEvent("endpoint", messageEndpoint)
                        flush()

                        while (true) {
                            val result = withTimeoutOrNull(SSE_KEEPALIVE_SECONDS.seconds) {
                                messageChannel.receiveCatching()
                            }
                            when {
                                result == null -> {
                                    write(": keepalive\n\n")
                                    flush()
                                }
                                result.isClosed -> break
                                else -> {
                                    writeSseEvent("message", result.getOrThrow())
                                    flush()
                                }
                            }
                        }
                    } finally {
                        sseSessions.remove(sessionId)
                        messageChannel.close()
                    }
                }
            }

            post {
                val droplet = call.requireGatewayDroplet(firestoreRepository) ?: return@post
                when (val result = call.processMcpRequest(
                    json = json,
                    droplet = droplet,
                    browserService = browserService,
                    application = this@configureBrowserMcpRoutes
                )) {
                    ProcessMcpRequestResult.Accepted -> call.respond(HttpStatusCode.Accepted)
                    ProcessMcpRequestResult.AlreadyResponded -> return@post
                    is ProcessMcpRequestResult.Response -> call.respondText(
                        text = result.payload.toString(),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
            }

            post("/messages") {
                val droplet = call.requireGatewayDroplet(firestoreRepository) ?: return@post
                val sessionId = call.request.queryParameters["session_id"].orEmpty()
                if (sessionId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "session_id is required"))
                    return@post
                }

                val session = sseSessions[sessionId]
                if (session == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown session_id"))
                    return@post
                }
                if (session.userId != droplet.userId) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Session does not belong to this gateway token"))
                    return@post
                }

                when (val result = call.processMcpRequest(
                    json = json,
                    droplet = droplet,
                    browserService = browserService,
                    application = this@configureBrowserMcpRoutes
                )) {
                    ProcessMcpRequestResult.Accepted -> call.respond(HttpStatusCode.Accepted)
                    ProcessMcpRequestResult.AlreadyResponded -> return@post
                    is ProcessMcpRequestResult.Response -> {
                        session.messages.trySend(result.payload.toString())
                        call.respond(HttpStatusCode.Accepted)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.processMcpRequest(
    json: Json,
    droplet: UserDropletInternal,
    browserService: BrowserService,
    application: Application
): ProcessMcpRequestResult {
    val body = runCatching { receiveText() }.getOrElse {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid MCP payload"))
        return ProcessMcpRequestResult.AlreadyResponded
    }

    val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
        respond(HttpStatusCode.BadRequest, mcpError(null, -32700, "Parse error"))
        return ProcessMcpRequestResult.AlreadyResponded
    }

    val id = request["id"]
    val method = request["method"]?.jsonPrimitive?.contentOrNull
    if (method.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, mcpError(id, -32600, "Missing method"))
        return ProcessMcpRequestResult.AlreadyResponded
    }

    val payload = runCatching {
        when (method) {
            "initialize" -> mcpResult(
                id,
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                    putJsonObject("serverInfo") {
                        put("name", MCP_SERVER_NAME)
                        put("version", "1.0.0")
                    }
                }
            )
            "notifications/initialized" -> if (id == null) null else mcpResult(id, buildJsonObject {})
            "ping" -> mcpResult(id, buildJsonObject {})
            "tools/list" -> mcpResult(
                id,
                buildJsonObject {
                    put("tools", cloudBrowserTools())
                }
            )
            "tools/call" -> handleToolCall(
                json = json,
                droplet = droplet,
                browserService = browserService,
                id = id,
                params = request["params"]?.jsonObject ?: buildJsonObject {}
            )
            else -> mcpError(id, -32601, "Method not found")
        }
    }.getOrElse { error ->
        application.log.error("Cloud browser MCP request failed: method=$method userId=${droplet.userId}", error)
        mcpError(id, -32000, error.message ?: "Cloud browser MCP failure")
    }
    return if (payload == null) ProcessMcpRequestResult.Accepted else ProcessMcpRequestResult.Response(payload)
}

private fun ApplicationCall.mcpMessageEndpoint(sessionId: String): String {
    val forwardedPrefix = request.header("X-Forwarded-Prefix")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.removeSuffix("/")
    val basePath = when {
        !forwardedPrefix.isNullOrBlank() -> forwardedPrefix
        else -> request.path().removeSuffix("/")
    }
    return "$basePath/messages?session_id=$sessionId"
}

private suspend fun Writer.writeSseEvent(event: String, data: String) {
    write("event: $event\n")
    for (line in data.lines()) {
        write("data: $line\n")
    }
    write("\n")
}

private suspend fun handleToolCall(
    json: Json,
    droplet: UserDropletInternal,
    browserService: BrowserService,
    id: JsonElement?,
    params: JsonObject
): JsonObject {
    val toolName = params["name"]?.jsonPrimitive?.contentOrNull
        ?: return toolError(id, "Tool name is required", "invalid_params")
    val args = params["arguments"]?.jsonObject ?: buildJsonObject {}

    return when (toolName) {
        "cloud_browser_open" -> {
            val profileId = args["profileId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (profileId.isBlank()) return toolError(id, "profileId is required", "invalid_params")
            val payload = browserService.createSession(
                droplet.userId,
                CreateBrowserSessionRequest(
                    profileId = profileId,
                    taskId = args["taskId"]?.jsonPrimitive?.contentOrNull,
                    initialUrl = args["initialUrl"]?.jsonPrimitive?.contentOrNull,
                    takeoverTimeoutSeconds = args["takeoverTimeoutSeconds"]?.jsonPrimitive?.intOrNull
                )
            )
            toolSuccess(id, json.encodeToJsonElement(BrowserSessionView.serializer(), payload))
        }
        "cloud_browser_exec" -> {
            val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val code = args["code"]?.jsonPrimitive?.contentOrNull
                ?: args["command"]?.jsonPrimitive?.contentOrNull
                ?: ""
            if (sessionId.isBlank() || code.isBlank()) {
                return toolError(id, "sessionId and code are required", "invalid_params")
            }
            val payload = browserService.executeSession(
                droplet.userId,
                BrowserExecRequest(
                    sessionId = sessionId,
                    code = code,
                    language = args["language"]?.jsonPrimitive?.contentOrNull ?: "bash",
                    timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull
                )
            )
            toolSuccess(id, json.encodeToJsonElement(BrowserExecResponse.serializer(), payload))
        }
        "cloud_browser_request_takeover" -> {
            val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sessionId.isBlank()) return toolError(id, "sessionId is required", "invalid_params")
            val payload = browserService.requestTakeover(
                droplet.userId,
                sessionId,
                TakeoverRequest(reason = args["reason"]?.jsonPrimitive?.contentOrNull)
            )
            toolSuccess(id, json.encodeToJsonElement(BrowserSessionView.serializer(), payload))
        }
        "cloud_browser_resume" -> {
            val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sessionId.isBlank()) return toolError(id, "sessionId is required", "invalid_params")
            val payload = browserService.resumeSession(
                droplet.userId,
                sessionId,
                ResumeBrowserSessionRequest(note = args["note"]?.jsonPrimitive?.contentOrNull)
            )
            toolSuccess(id, json.encodeToJsonElement(BrowserSessionView.serializer(), payload))
        }
        "cloud_browser_close" -> {
            val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sessionId.isBlank()) return toolError(id, "sessionId is required", "invalid_params")
            val payload = browserService.closeSession(droplet.userId, sessionId)
            toolSuccess(id, json.encodeToJsonElement(BrowserSessionView.serializer(), payload))
        }
        else -> toolError(id, "Unknown tool '$toolName'", "tool_not_found")
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.requireGatewayDroplet(
    firestoreRepository: FirestoreRepository
): UserDropletInternal? {
    val authHeader = request.header(HttpHeaders.Authorization).orEmpty()
    val token = authHeader.removePrefix("Bearer ").trim()
    if (!authHeader.startsWith("Bearer ") || token.isBlank()) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing or invalid Authorization header"))
        return null
    }

    val droplet = firestoreRepository.getUserDropletInternalByGatewayToken(token)
    if (droplet == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid gateway token"))
        return null
    }
    return droplet
}

private fun cloudBrowserTools() = buildJsonArray {
    add(toolDefinition("cloud_browser_open", "Create a new SuprClaw cloud browser session.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("profileId") { put("type", "string") }
            putJsonObject("taskId") { put("type", "string") }
            putJsonObject("initialUrl") { put("type", "string") }
            putJsonObject("takeoverTimeoutSeconds") { put("type", "integer") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("profileId")) })
    }))
    add(toolDefinition("cloud_browser_exec", "Execute a browser command in the active Firecrawl sandbox session and return structured page state.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string") }
            putJsonObject("code") { put("type", "string") }
            putJsonObject("command") { put("type", "string") }
            putJsonObject("language") { put("type", "string") }
            putJsonObject("timeoutSeconds") { put("type", "integer") }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("sessionId"))
            add(JsonPrimitive("code"))
        })
    }))
    add(toolDefinition("cloud_browser_request_takeover", "Request human takeover for CAPTCHA, MFA, or sensitive browser steps.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string") }
            putJsonObject("reason") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
    }))
    add(toolDefinition("cloud_browser_resume", "Resume the agent after the user completes takeover.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string") }
            putJsonObject("note") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
    }))
    add(toolDefinition("cloud_browser_close", "Close a browser session and persist profile state when supported by the provider.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
    }))
}

private fun toolDefinition(name: String, description: String, inputSchema: JsonObject): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put("inputSchema", inputSchema)
}

private fun toolSuccess(id: JsonElement?, payload: JsonElement): JsonObject = mcpResult(
    id,
    buildJsonObject {
        put("content", buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", payload.toString())
                }
            )
        })
        put("structuredContent", payload)
        put("isError", false)
    }
)

private fun toolError(id: JsonElement?, message: String, code: String): JsonObject = mcpResult(
    id,
    buildJsonObject {
        put("content", buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", message)
                }
            )
        })
        put(
            "structuredContent",
            buildJsonObject {
                put("error", message)
                put("code", code)
            }
        )
        put("isError", true)
    }
)

private fun mcpResult(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
    val safeId: JsonElement = id ?: JsonPrimitive(0)
    put("jsonrpc", "2.0")
    put("id", safeId)
    put("result", result)
}

private fun mcpError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    val safeId: JsonElement = id ?: JsonPrimitive(0)
    put("jsonrpc", "2.0")
    put("id", safeId)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

private inline fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
) {
    put(key, buildJsonObject(builder))
}
