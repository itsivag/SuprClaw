package com.suprbeta.browser

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class BrowserProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class BrowserCapacityException(val retryAfterSeconds: Int, message: String) : RuntimeException(message)
class BrowserFeatureDisabledException : RuntimeException("Cloud browser is disabled")

interface BrowserProviderClient {
    suspend fun createSession(profileName: String, initialUrl: String?, ttlSeconds: Int, activityTtlSeconds: Int): ProviderBrowserSession
    suspend fun deleteSession(providerSessionId: String)
    suspend fun listActiveSessions(): List<ProviderBrowserSessionSummary>
    suspend fun executeSession(providerSessionId: String, code: String, language: String, timeoutSeconds: Int? = null): ProviderBrowserExecution
    suspend fun applyMobileEmulation(cdpUrl: String)
    suspend fun navigateToUrl(cdpUrl: String, url: String)
    suspend fun sendKeepalive(cdpUrl: String)
}

class FirecrawlBrowserClient(
    private val httpClient: HttpClient,
    private val config: BrowserConfig,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : BrowserProviderClient {

    private companion object {
        const val MOBILE_VIEWPORT_WIDTH = 390
        const val MOBILE_VIEWPORT_HEIGHT = 844
        const val MOBILE_WINDOW_WIDTH = 430
        const val MOBILE_WINDOW_HEIGHT = 980
    }

    override suspend fun createSession(
        profileName: String,
        initialUrl: String?,
        ttlSeconds: Int,
        activityTtlSeconds: Int
    ): ProviderBrowserSession {
        if (!config.enabled) throw BrowserFeatureDisabledException()
        if (config.firecrawlApiKey.isBlank()) {
            throw BrowserProviderException("FIRECRAWL_API_KEY is not configured")
        }

        val response = httpClient.post("${config.apiBaseUrl}/v2/browser") {
            header(HttpHeaders.Authorization, "Bearer ${config.firecrawlApiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateBrowserPayload(
                    ttl = ttlSeconds,
                    activityTtl = activityTtlSeconds,
                    streamWebView = true,
                    profile = BrowserProfilePayload(name = profileName, saveChanges = true),
                    url = initialUrl?.takeIf { it.isNotBlank() }
                )
            )
        }

        if (response.status.value == 429) {
            throw BrowserCapacityException(config.retryAfterSeconds(), "Firecrawl browser capacity limit reached")
        }
        if (!response.status.isSuccess()) {
            throw BrowserProviderException("Firecrawl browser create failed with status ${response.status.value}")
        }

        val payload = response.body<CreateBrowserResponse>()
        if (!payload.success || payload.id.isBlank() || payload.cdpUrl.isBlank() || payload.liveViewUrl.isBlank()) {
            throw BrowserProviderException("Firecrawl browser create returned incomplete session metadata")
        }
        val interactiveLiveViewUrl = buildInteractiveLiveViewUrl(payload.liveViewUrl)
        return ProviderBrowserSession(
            id = payload.id,
            cdpUrl = payload.cdpUrl,
            liveViewUrl = payload.liveViewUrl,
            interactiveLiveViewUrl = interactiveLiveViewUrl,
            expiresAt = payload.expiresAt
        )
    }

    override suspend fun deleteSession(providerSessionId: String) {
        if (providerSessionId.isBlank()) return
        val providerSummary = runCatching { listActiveSessions().firstOrNull { it.id == providerSessionId } }.getOrNull()
        providerSummary?.cdpUrl?.takeIf { it.isNotBlank() }?.let { cdpUrl ->
            runCatching { closeBrowserViaCdp(cdpUrl) }
        }
        val response = httpClient.delete("${config.apiBaseUrl}/v2/browser/$providerSessionId") {
            header(HttpHeaders.Authorization, "Bearer ${config.firecrawlApiKey}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("id", providerSessionId) })
        }
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw BrowserProviderException("Firecrawl browser delete failed with status ${response.status.value}")
        }
        waitForSessionRemoval(providerSessionId)
    }

    override suspend fun listActiveSessions(): List<ProviderBrowserSessionSummary> {
        if (!config.enabled || config.firecrawlApiKey.isBlank()) return emptyList()
        val response = httpClient.get("${config.apiBaseUrl}/v2/browser") {
            header(HttpHeaders.Authorization, "Bearer ${config.firecrawlApiKey}")
        }
        if (!response.status.isSuccess()) {
            throw BrowserProviderException("Firecrawl browser list failed with status ${response.status.value}")
        }
        val payload = response.body<ListBrowserSessionsResponse>()
        return payload.sessions.filter { it.status.equals("active", ignoreCase = true) }.map {
            ProviderBrowserSessionSummary(
                id = it.id,
                status = it.status,
                liveViewUrl = it.liveViewUrl,
                cdpUrl = it.cdpUrl,
                createdAt = it.createdAt,
                lastActivity = it.lastActivity
            )
        }
    }

    override suspend fun executeSession(
        providerSessionId: String,
        code: String,
        language: String,
        timeoutSeconds: Int?
    ): ProviderBrowserExecution {
        if (providerSessionId.isBlank()) {
            throw BrowserProviderException("Provider session id is required for execute")
        }
        if (code.isBlank()) {
            throw BrowserProviderException("Execute code is required")
        }

        val response = httpClient.post("${config.apiBaseUrl}/v2/browser/$providerSessionId/execute") {
            header(HttpHeaders.Authorization, "Bearer ${config.firecrawlApiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                ExecuteBrowserPayload(
                    code = code,
                    language = language,
                    timeout = timeoutSeconds
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw BrowserProviderException("Firecrawl browser execute failed with status ${response.status.value}")
        }

        val body = response.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val node = root["data"]?.jsonObject ?: root
        val errorNode = node["error"]
        val errorMessage = when {
            errorNode == null -> null
            errorNode is JsonObject -> errorNode["message"]?.jsonPrimitive?.contentOrNull ?: errorNode.toString()
            else -> errorNode.jsonPrimitive.contentOrNull
        }

        return ProviderBrowserExecution(
            success = root["success"]?.jsonPrimitive?.booleanOrNull ?: node["success"]?.jsonPrimitive?.booleanOrNull ?: true,
            result = node["result"]?.let { elementToText(it) },
            stdout = node["stdout"]?.jsonPrimitive?.contentOrNull,
            stderr = node["stderr"]?.jsonPrimitive?.contentOrNull,
            exitCode = node["exitCode"]?.jsonPrimitive?.intOrNull,
            killed = node["killed"]?.jsonPrimitive?.booleanOrNull ?: false,
            error = errorMessage,
            raw = body
        )
    }

    override suspend fun applyMobileEmulation(cdpUrl: String) {
        val session = httpClient.webSocketSession(cdpUrl)
        try {
            val pageTarget = attachToPageTarget(session)
            resizeBrowserWindow(session, pageTarget.targetId)
            sendCdpCommand(
                session = session,
                id = 1,
                method = "Page.enable",
                sessionId = pageTarget.sessionId,
                params = buildJsonObject {}
            )
            sendCdpCommand(
                session = session,
                id = 2,
                method = "Network.enable",
                sessionId = pageTarget.sessionId,
                params = buildJsonObject {}
            )
            sendCdpCommand(
                session = session,
                id = 3,
                method = "Emulation.setDeviceMetricsOverride",
                sessionId = pageTarget.sessionId,
                params = buildJsonObject {
                    put("width", MOBILE_VIEWPORT_WIDTH)
                    put("height", MOBILE_VIEWPORT_HEIGHT)
                    put("deviceScaleFactor", 3)
                    put("mobile", true)
                    put("screenWidth", MOBILE_VIEWPORT_WIDTH)
                    put("screenHeight", MOBILE_VIEWPORT_HEIGHT)
                }
            )
            sendCdpCommand(
                session = session,
                id = 4,
                method = "Network.setUserAgentOverride",
                sessionId = pageTarget.sessionId,
                params = buildJsonObject {
                    put(
                        "userAgent",
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    )
                    put("acceptLanguage", "en-US,en;q=0.9")
                    put("platform", "iPhone")
                }
            )
            sendCdpCommand(
                session = session,
                id = 5,
                method = "Emulation.setTouchEmulationEnabled",
                sessionId = pageTarget.sessionId,
                params = buildJsonObject {
                    put("enabled", true)
                    put("maxTouchPoints", 5)
                }
            )
        } finally {
            runCatching { session.close() }
        }
    }

    override suspend fun sendKeepalive(cdpUrl: String) {
        val session = httpClient.webSocketSession(cdpUrl)
        try {
            val pageSessionId = attachToPageTarget(session).sessionId
            sendCdpCommand(
                session = session,
                id = 99,
                method = "Runtime.evaluate",
                sessionId = pageSessionId,
                params = buildJsonObject {
                    put("expression", "1+1")
                    put("returnByValue", true)
                }
            )
        } finally {
            runCatching { session.close() }
        }
    }

    override suspend fun navigateToUrl(cdpUrl: String, url: String) {
        if (url.isBlank()) return

        val session = httpClient.webSocketSession(cdpUrl)
        try {
            val pageSessionId = attachToPageTarget(session).sessionId
            sendCdpCommand(
                session = session,
                id = 110,
                method = "Page.enable",
                sessionId = pageSessionId,
                params = buildJsonObject {}
            )
            sendCdpCommand(
                session = session,
                id = 111,
                method = "Page.navigate",
                sessionId = pageSessionId,
                params = buildJsonObject {
                    put("url", url)
                }
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun sendCdpCommand(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        id: Int,
        method: String,
        sessionId: String? = null,
        params: JsonObject
    ) {
        session.send(Frame.Text(json.encodeToString(CdpMessage(id = id, method = method, params = params, sessionId = sessionId))))
        withTimeout(10_000) {
            while (true) {
                val frame = session.incoming.receive()
                if (frame !is Frame.Text) continue
                val obj = json.parseToJsonElement(frame.readText()).jsonObject
                val responseId = obj["id"]?.jsonPrimitive?.intOrNull
                if (responseId != id) continue
                val responseSessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
                if (sessionId != null && responseSessionId != null && responseSessionId != sessionId) continue
                val error = obj["error"]?.jsonObject
                if (error != null) {
                    val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown CDP error"
                    throw BrowserProviderException("CDP command '$method' failed: $message")
                }
                return@withTimeout
            }
        }
    }

    private suspend fun attachToPageTarget(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
    ): AttachedPageTarget {
        val targets = sendCdpCommandForResult(
            session = session,
            id = 501,
            method = "Target.getTargets",
            params = buildJsonObject {}
        )
        val pageTarget = targets["targetInfos"]
            ?: throw BrowserProviderException("CDP Target.getTargets returned no targets")
        val pageId = pageTarget.jsonArray.firstOrNull { info ->
            info.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "page"
        }?.jsonObject?.get("targetId")?.jsonPrimitive?.contentOrNull
            ?: throw BrowserProviderException("CDP browser session exposed no page target")

        val attached = sendCdpCommandForResult(
            session = session,
            id = 502,
            method = "Target.attachToTarget",
            params = buildJsonObject {
                put("targetId", pageId)
                put("flatten", true)
            }
        )
        val sessionId = attached["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: throw BrowserProviderException("CDP Target.attachToTarget returned no sessionId")
        return AttachedPageTarget(targetId = pageId, sessionId = sessionId)
    }

    private suspend fun resizeBrowserWindow(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        targetId: String
    ) {
        val window = sendCdpCommandForResult(
            session = session,
            id = 503,
            method = "Browser.getWindowForTarget",
            params = buildJsonObject {
                put("targetId", targetId)
            }
        )
        val windowId = window["windowId"]?.jsonPrimitive?.intOrNull
            ?: throw BrowserProviderException("CDP Browser.getWindowForTarget returned no windowId")

        sendCdpCommand(
            session = session,
            id = 504,
            method = "Browser.setWindowBounds",
            params = buildJsonObject {
                put("windowId", windowId)
                put("bounds", buildJsonObject {
                    put("left", 0)
                    put("top", 0)
                    put("width", MOBILE_WINDOW_WIDTH)
                    put("height", MOBILE_WINDOW_HEIGHT)
                })
            }
        )
    }

    private suspend fun sendCdpCommandForResult(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        id: Int,
        method: String,
        params: JsonObject
    ): JsonObject {
        session.send(Frame.Text(json.encodeToString(CdpMessage(id = id, method = method, params = params))))
        return withTimeout<JsonObject>(10_000) {
            var response: JsonObject? = null
            while (response == null) {
                val frame = session.incoming.receive()
                if (frame !is Frame.Text) continue
                val obj = json.parseToJsonElement(frame.readText()).jsonObject
                if (obj["id"]?.jsonPrimitive?.intOrNull != id) continue
                val error = obj["error"]?.jsonObject
                if (error != null) {
                    val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown CDP error"
                    throw BrowserProviderException("CDP command '$method' failed: $message")
                }
                response = obj["result"]?.jsonObject ?: buildJsonObject {}
            }
            response ?: throw BrowserProviderException("Timed out waiting for CDP result for '$method'")
        }
    }

    private fun buildInteractiveLiveViewUrl(liveViewUrl: String): String {
        val uri = URI(liveViewUrl)
        val existing = uri.query?.takeIf { it.isNotBlank() }?.let { "$it&" } ?: ""
        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            "${existing}interactive=true",
            uri.fragment
        ).toString()
    }

    private suspend fun waitForSessionRemoval(providerSessionId: String) {
        val deadlineMillis = System.currentTimeMillis() + config.deleteConfirmTimeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadlineMillis) {
            val stillActive = runCatching { listActiveSessions().any { it.id == providerSessionId } }
                .getOrElse { true }
            if (!stillActive) return
            delay(config.deleteConfirmPollSeconds * 1_000L)
        }
        throw BrowserProviderException(
            "Firecrawl browser delete did not fully close session $providerSessionId within ${config.deleteConfirmTimeoutSeconds}s"
        )
    }

    private suspend fun closeBrowserViaCdp(cdpUrl: String) {
        val session = httpClient.webSocketSession(cdpUrl)
        try {
            session.send(
                Frame.Text(
                    json.encodeToString(
                        CdpMessage(
                            id = 900,
                            method = "Browser.close",
                            params = buildJsonObject {}
                        )
                    )
                )
            )
            withTimeout(5_000) {
                while (true) {
                    val frame = session.incoming.receive()
                    if (frame !is Frame.Text) continue
                    val obj = json.parseToJsonElement(frame.readText()).jsonObject
                    if (obj["id"]?.jsonPrimitive?.intOrNull != 900) continue
                    val error = obj["error"]?.jsonObject
                    if (error != null) {
                        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown CDP error"
                        throw BrowserProviderException("CDP command 'Browser.close' failed: $message")
                    }
                    return@withTimeout
                }
            }
        } finally {
            runCatching { session.close() }
        }
    }
}

data class ProviderBrowserSession(
    val id: String,
    val cdpUrl: String,
    val liveViewUrl: String,
    val interactiveLiveViewUrl: String,
    val expiresAt: String
)

data class ProviderBrowserSessionSummary(
    val id: String,
    val status: String,
    val liveViewUrl: String? = null,
    val cdpUrl: String? = null,
    val createdAt: String? = null,
    val lastActivity: String? = null
)

data class ProviderBrowserExecution(
    val success: Boolean,
    val result: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val killed: Boolean = false,
    val error: String? = null,
    val raw: String? = null
)

@Serializable
private data class CreateBrowserPayload(
    val ttl: Int,
    val activityTtl: Int,
    val streamWebView: Boolean,
    val profile: BrowserProfilePayload,
    val url: String? = null
)

@Serializable
private data class ExecuteBrowserPayload(
    val code: String,
    val language: String,
    val timeout: Int? = null
)

@Serializable
private data class BrowserProfilePayload(
    val name: String,
    val saveChanges: Boolean
)

@Serializable
private data class CreateBrowserResponse(
    val success: Boolean = false,
    val id: String = "",
    val cdpUrl: String = "",
    val liveViewUrl: String = "",
    val expiresAt: String = ""
)

@Serializable
private data class ListBrowserSessionsResponse(
    val success: Boolean = false,
    val sessions: List<ListBrowserSessionItem> = emptyList()
)

@Serializable
private data class ListBrowserSessionItem(
    val id: String = "",
    val status: String = "",
    val cdpUrl: String? = null,
    val liveViewUrl: String? = null,
    val createdAt: String? = null,
    val lastActivity: String? = null
)

@Serializable
private data class CdpMessage(
    val id: Int,
    val method: String,
    val params: JsonObject,
    val sessionId: String? = null
)

private data class AttachedPageTarget(
    val targetId: String,
    val sessionId: String
)

private fun elementToText(element: kotlinx.serialization.json.JsonElement): String? {
    return when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }
}
