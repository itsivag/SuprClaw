package com.suprbeta.integration

import com.suprbeta.browser.BrowserConfig
import com.suprbeta.browser.FirecrawlBrowserClient
import com.suprbeta.createHttpClient
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class FirecrawlBrowserIntegrationTest {
    private val log = LoggerFactory.getLogger(FirecrawlBrowserIntegrationTest::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private fun env(key: String): String = System.getenv(key) ?: dotenv[key] ?: ""

    private fun requireLiveFirecrawlOptIn() {
        assumeTrue(env("INTEGRATION_TEST").equals("true", ignoreCase = true) || env("INTG_FIRECRAWL_LIVE").equals("true", ignoreCase = true)) {
            "Set INTEGRATION_TEST=true or INTG_FIRECRAWL_LIVE=true to run live Firecrawl integration tests"
        }
    }

    private fun config(): BrowserConfig {
        requireLiveFirecrawlOptIn()
        val apiKey = env("FIRECRAWL_API_KEY")
        assumeTrue(apiKey.isNotBlank()) { "FIRECRAWL_API_KEY not set — skipping Firecrawl browser integration tests" }
        return BrowserConfig(
            enabled = true,
            apiBaseUrl = env("FIRECRAWL_API_BASE_URL").ifBlank { "https://api.firecrawl.dev" },
            publicBaseUrl = "https://api.suprclaw.com",
            firecrawlApiKey = apiKey,
            defaultTtlSeconds = 600,
            defaultTakeoverTimeoutSeconds = 600,
            minTakeoverTimeoutSeconds = 300,
            maxTakeoverTimeoutSeconds = 1800,
            gracefulCloseMarginSeconds = 120,
            keepaliveIntervalSeconds = 30,
            heartbeatIntervalSeconds = 15,
            staleHeartbeatSeconds = 45,
            reconciliationIntervalSeconds = 30,
            globalActiveSessionLimit = 20,
            perUserActiveSessionLimit = 2,
            retryAfterMinSeconds = 30,
            retryAfterMaxSeconds = 90,
            deleteConfirmTimeoutSeconds = 30,
            deleteConfirmPollSeconds = 2
        )
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Test
    fun `create browser session apply mobile emulation and inspect live view`() = runBlocking {
        val httpClient = createHttpClient()
        val config = config()
        val firecrawl = FirecrawlBrowserClient(httpClient, config)
        val profileName = "sc:intg:${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val targetUrl = env("INTG_FIRECRAWL_BROWSER_URL").ifBlank { "https://example.com" }

        var sessionId: String? = null
        try {
            val session = firecrawl.createSession(
                profileName = profileName,
                initialUrl = null,
                ttlSeconds = 600,
                activityTtlSeconds = 600
            )
            sessionId = session.id
            log.info("[INTG] Created Firecrawl browser session {}", session.id)

            firecrawl.applyMobileEmulation(session.cdpUrl)

            val snapshot = inspectPage(httpClient, session.cdpUrl, targetUrl)
            log.info("[INTG] Page snapshot url={} title={} width={} ua={}", snapshot.url, snapshot.title, snapshot.innerWidth, snapshot.userAgent)

            assertTrue(snapshot.innerWidth in 320..430, "Expected mobile width after emulation, got ${snapshot.innerWidth}")
            assertTrue(snapshot.url.contains("example.com"), "Expected navigation to target url, got ${snapshot.url}")
            if (snapshot.maxTouchPoints < 1) {
                log.warn("[INTG] Touch emulation did not persist across fresh CDP reconnection: {}", snapshot.maxTouchPoints)
            }
            if (!(snapshot.userAgent.contains("iPhone") || snapshot.userAgent.contains("Mobile"))) {
                log.warn("[INTG] User agent override did not persist across fresh CDP reconnection: {}", snapshot.userAgent)
            }

            val liveViewBody = fetchTextWithRetry(httpClient, session.liveViewUrl)
            assertTrue(liveViewBody.isNotBlank(), "Expected live view body to be non-empty")

            val interactiveStatus = fetchStatusWithRetry(httpClient, session.interactiveLiveViewUrl)
            assertTrue(interactiveStatus.isSuccess(), "Expected interactiveLiveViewUrl to respond successfully, got $interactiveStatus")

            firecrawl.sendKeepalive(session.cdpUrl)
            val active = firecrawl.listActiveSessions()
            assertTrue(active.any { it.id == session.id }, "Expected created session to appear in active session list")
        } finally {
            sessionId?.let {
                runCatching { firecrawl.deleteSession(it) }
                    .onFailure { error -> log.warn("[INTG] Failed to delete Firecrawl session {}: {}", it, error.message) }
            }
            httpClient.close()
        }
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Test
    fun `raw cdp mobile emulation works within attached page session`() = runBlocking {
        val httpClient = createHttpClient()
        val config = config()
        val firecrawl = FirecrawlBrowserClient(httpClient, config)
        val profileName = "sc:intg:${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val targetUrl = env("INTG_FIRECRAWL_BROWSER_URL").ifBlank { "https://example.com" }

        var sessionId: String? = null
        try {
            val session = firecrawl.createSession(profileName, null, ttlSeconds = 600, activityTtlSeconds = 600)
            sessionId = session.id

            val snapshot = withCdpSession(httpClient, session.cdpUrl) {
                applyMobileOverrides()
                preparePage(targetUrl)
                val raw = evaluate(
                    """
                    JSON.stringify({
                      url: location.href,
                      title: document.title,
                      userAgent: navigator.userAgent,
                      innerWidth: window.innerWidth,
                      innerHeight: window.innerHeight,
                      maxTouchPoints: navigator.maxTouchPoints || 0
                    })
                    """.trimIndent()
                )?.jsonPrimitive?.contentOrNull ?: error("Expected Runtime.evaluate result")
                json.decodeFromString<PageSnapshot>(raw)
            }

            assertTrue(snapshot.userAgent.contains("iPhone") || snapshot.userAgent.contains("Mobile"), "Expected mobile user agent in attached session, got ${snapshot.userAgent}")
            assertTrue(snapshot.innerWidth in 320..430, "Expected mobile width in attached session, got ${snapshot.innerWidth}")
            assertTrue(snapshot.maxTouchPoints >= 1, "Expected touch emulation in attached session, got ${snapshot.maxTouchPoints}")
        } finally {
            sessionId?.let { runCatching { firecrawl.deleteSession(it) } }
            httpClient.close()
        }
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Test
    fun `browser execute via agent-browser returns snapshot on live firecrawl sandbox`() = runBlocking {
        val httpClient = createHttpClient()
        val config = config()
        val firecrawl = FirecrawlBrowserClient(httpClient, config)
        val profileName = "sc:intg:${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val targetUrl = env("INTG_FIRECRAWL_BROWSER_URL").ifBlank { "https://example.com" }

        var sessionId: String? = null
        try {
            val session = firecrawl.createSession(profileName, null, ttlSeconds = 600, activityTtlSeconds = 600)
            sessionId = session.id
            withCdpSession(httpClient, session.cdpUrl) {
                applyMobileOverrides()
            }

            val openExecution = firecrawl.executeSession(
                providerSessionId = session.id,
                language = "bash",
                timeoutSeconds = 45,
                code = "agent-browser open $targetUrl"
            )
            assertTrue(openExecution.success, "Expected Firecrawl bash open to succeed: ${openExecution.raw}")

            val snapshotExecution = firecrawl.executeSession(
                providerSessionId = session.id,
                language = "bash",
                timeoutSeconds = 45,
                code = "agent-browser snapshot"
            )
            log.info(
                "[INTG] Bash snapshot stdout={} result={} raw={}",
                snapshotExecution.stdout?.take(500),
                snapshotExecution.result?.take(500),
                snapshotExecution.raw?.take(500)
            )

            assertTrue(snapshotExecution.success, "Expected Firecrawl snapshot to succeed: ${snapshotExecution.raw}")
            val snapshotText = buildString {
                appendLine(snapshotExecution.stdout.orEmpty())
                appendLine(snapshotExecution.result.orEmpty())
                appendLine(snapshotExecution.raw.orEmpty())
            }
            assertTrue(snapshotText.isNotBlank(), "Expected snapshot output to be non-empty")
            assertTrue(
                snapshotText.contains("example.com", ignoreCase = true) ||
                    snapshotText.contains("Example Domain", ignoreCase = true),
                "Expected snapshot output to mention the opened page, got: ${snapshotText.take(500)}"
            )
        } finally {
            sessionId?.let { runCatching { firecrawl.deleteSession(it) } }
            httpClient.close()
        }
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Disabled("Live Firecrawl test on 2026-03-12 returned success with empty stdout/result for node page scripts despite docs promising output; re-enable when provider behavior is reliable")
    @Test
    fun `browser execute node script returns page snapshot on live firecrawl sandbox`() = runBlocking {
        val httpClient = createHttpClient()
        val config = config()
        val firecrawl = FirecrawlBrowserClient(httpClient, config)
        val profileName = "sc:intg:${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val targetUrl = env("INTG_FIRECRAWL_BROWSER_URL").ifBlank { "https://example.com" }

        var sessionId: String? = null
        try {
            val session = firecrawl.createSession(profileName, null, ttlSeconds = 600, activityTtlSeconds = 600)
            sessionId = session.id
            withCdpSession(httpClient, session.cdpUrl) {
                applyMobileOverrides()
            }

            val execution = firecrawl.executeSession(
                providerSessionId = session.id,
                language = "node",
                timeoutSeconds = 45,
                code = """
                    await page.goto('$targetUrl', { waitUntil: 'domcontentloaded' });
                    const snapshot = await page.evaluate(() => ({
                      url: location.href,
                      title: document.title,
                      userAgent: navigator.userAgent,
                      innerWidth: window.innerWidth,
                      innerHeight: window.innerHeight,
                      maxTouchPoints: navigator.maxTouchPoints || 0
                    }));
                    console.log(JSON.stringify(snapshot));
                """.trimIndent()
            )
            log.info(
                "[INTG] Execute stdout={} result={} raw={}",
                execution.stdout?.take(500),
                execution.result?.take(500),
                execution.raw?.take(500)
            )

            assertTrue(execution.success, "Expected Firecrawl execute to succeed: ${execution.raw}")
            val snapshot = extractPageSnapshot(execution.stdout, execution.result, execution.raw)

            assertTrue(snapshot.url.contains("example.com"), "Expected execute navigation to target url, got ${snapshot.url}")
            assertTrue(snapshot.userAgent.contains("iPhone") || snapshot.userAgent.contains("Mobile"), "Expected mobile user agent in execute output, got ${snapshot.userAgent}")
            assertTrue(snapshot.innerWidth in 320..430, "Expected mobile width in execute output, got ${snapshot.innerWidth}")
            assertTrue(snapshot.maxTouchPoints >= 1, "Expected touch emulation in execute output, got ${snapshot.maxTouchPoints}")
        } finally {
            sessionId?.let { runCatching { firecrawl.deleteSession(it) } }
            httpClient.close()
        }
    }

    @org.junit.jupiter.api.Timeout(value = 7, unit = TimeUnit.MINUTES)
    @Disabled("Live Firecrawl test on 2026-03-12 did not restore cookie/localStorage across explicit close; re-enable when provider profile persistence is verified")
    @Test
    fun `browser profile persists cookies across explicit close`() = runBlocking {
        val httpClient = createHttpClient()
        val config = config()
        val firecrawl = FirecrawlBrowserClient(httpClient, config)
        val profileName = "sc:intg:${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val targetUrl = env("INTG_FIRECRAWL_BROWSER_URL").ifBlank { "https://example.com" }
        val localStorageKey = "suprclawIntegration"
        val localStorageValue = "persisted-${Instant.now().epochSecond}"
        val cookieKey = "suprclawCookie"
        val cookieValue = "cookie-${Instant.now().epochSecond}"

        var firstSessionId: String? = null
        var secondSessionId: String? = null
        try {
            val first = firecrawl.createSession(profileName, null, ttlSeconds = 600, activityTtlSeconds = 600)
            firstSessionId = first.id
            firecrawl.applyMobileEmulation(first.cdpUrl)
            withCdpSession(httpClient, first.cdpUrl) {
                preparePage(targetUrl)
                evaluate("localStorage.setItem('$localStorageKey', '$localStorageValue'); document.cookie = '$cookieKey=$cookieValue; path=/'; 'ok';")
            }
            firecrawl.deleteSession(first.id)
            firstSessionId = null

            delay(3_000)

            val second = firecrawl.createSession(profileName, null, ttlSeconds = 600, activityTtlSeconds = 600)
            secondSessionId = second.id
            firecrawl.applyMobileEmulation(second.cdpUrl)
            val restored = withCdpSession(httpClient, second.cdpUrl) {
                preparePage(targetUrl)
                val raw = evaluate(
                    """
                    JSON.stringify({
                      localStorageValue: localStorage.getItem('$localStorageKey'),
                      cookieString: document.cookie || ''
                    })
                    """.trimIndent()
                )?.jsonPrimitive?.contentOrNull ?: error("Expected restored storage snapshot")
                json.decodeFromString<StorageSnapshot>(raw)
            }

            assertTrue(
                restored.cookieString.split(";").map { it.trim() }.contains("$cookieKey=$cookieValue"),
                "Expected cookie value to persist across explicit close and recreate, got ${restored.cookieString}"
            )
            if (restored.localStorageValue != localStorageValue) {
                log.warn(
                    "[INTG] Firecrawl profile did not restore localStorage for profile {}. expected={} actual={}",
                    profileName,
                    localStorageValue,
                    restored.localStorageValue
                )
            }
        } finally {
            firstSessionId?.let { runCatching { firecrawl.deleteSession(it) } }
            secondSessionId?.let { runCatching { firecrawl.deleteSession(it) } }
            httpClient.close()
        }
    }

    private suspend fun inspectPage(httpClient: io.ktor.client.HttpClient, cdpUrl: String, targetUrl: String): PageSnapshot {
        return withCdpSession(httpClient, cdpUrl) {
            preparePage(targetUrl)
            val evaluated = evaluate(
                """
                JSON.stringify({
                  url: location.href,
                  title: document.title,
                  userAgent: navigator.userAgent,
                  innerWidth: window.innerWidth,
                  innerHeight: window.innerHeight,
                  maxTouchPoints: navigator.maxTouchPoints || 0
                })
                """.trimIndent()
            )
            val raw = evaluated?.jsonPrimitive?.contentOrNull ?: error("Expected Runtime.evaluate result")
            json.decodeFromString<PageSnapshot>(raw)
        }
    }

    private suspend fun fetchTextWithRetry(
        httpClient: io.ktor.client.HttpClient,
        url: String,
        attempts: Int = 3
    ): String {
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            val result = runCatching {
                val response = httpClient.get(url)
                check(response.status.isSuccess()) { "Expected success status from $url, got ${response.status}" }
                response.bodyAsText()
            }
            result.getOrNull()?.let { return it }
            lastFailure = result.exceptionOrNull()
            if (attempt < attempts - 1) {
                delay((attempt + 1) * 1_000L)
            }
        }
        throw lastFailure ?: IllegalStateException("Failed to read $url after $attempts attempts")
    }

    private suspend fun fetchStatusWithRetry(
        httpClient: io.ktor.client.HttpClient,
        url: String,
        attempts: Int = 3
    ): HttpStatusCode {
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            val result = runCatching { httpClient.get(url).status }
            result.getOrNull()?.let { return it }
            lastFailure = result.exceptionOrNull()
            if (attempt < attempts - 1) {
                delay((attempt + 1) * 1_000L)
            }
        }
        throw lastFailure ?: IllegalStateException("Failed to fetch $url after $attempts attempts")
    }

    private fun extractPageSnapshot(vararg candidates: String?): PageSnapshot {
        candidates.forEach { candidate ->
            if (candidate.isNullOrBlank()) return@forEach
            extractPageSnapshotFromText(candidate)?.let { return it }
        }
        error("Expected page snapshot JSON in Firecrawl execute output")
    }

    private fun extractPageSnapshotFromText(candidate: String): PageSnapshot? {
        val attempts = buildList {
            add(candidate.trim())
            candidate.lines()
                .map { it.trim() }
                .filter { it.startsWith("{") && it.endsWith("}") }
                .forEach(::add)
        }
        for (attempt in attempts) {
            if (attempt.isBlank()) continue
            val element = runCatching { json.parseToJsonElement(attempt) }.getOrNull() ?: continue
            extractPageSnapshotFromElement(element)?.let { return it }
        }
        return null
    }

    private fun extractPageSnapshotFromElement(element: JsonElement?): PageSnapshot? {
        return when (element) {
            null -> null
            is JsonObject -> {
                parsePageSnapshot(element)
                    ?: element.values.firstNotNullOfOrNull { extractPageSnapshotFromElement(it) }
            }
            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { extractPageSnapshotFromElement(it) }
            else -> {
                val nested = element.jsonPrimitive.contentOrNull ?: return null
                runCatching { json.parseToJsonElement(nested) }.getOrNull()?.let { extractPageSnapshotFromElement(it) }
            }
        }
    }

    private fun parsePageSnapshot(element: JsonObject): PageSnapshot? {
        val url = element["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = element["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val userAgent = element["userAgent"]?.jsonPrimitive?.contentOrNull ?: return null
        val innerWidth = element["innerWidth"]?.jsonPrimitive?.intOrNull ?: return null
        val innerHeight = element["innerHeight"]?.jsonPrimitive?.intOrNull ?: return null
        val maxTouchPoints = element["maxTouchPoints"]?.jsonPrimitive?.intOrNull ?: return null
        return PageSnapshot(
            url = url,
            title = title,
            userAgent = userAgent,
            innerWidth = innerWidth,
            innerHeight = innerHeight,
            maxTouchPoints = maxTouchPoints
        )
    }

    private suspend fun <T> withCdpSession(
        httpClient: io.ktor.client.HttpClient,
        cdpUrl: String,
        block: suspend CdpSession.() -> T
    ): T {
        val ws = httpClient.webSocketSession(cdpUrl)
        return try {
            val cdp = CdpSession(ws, json)
            cdp.enable()
            block(cdp)
        } finally {
            runCatching { ws.close() }
        }
    }

    @Serializable
    private data class CdpMessage(
        val id: Int,
        val method: String,
        val params: JsonObject? = null,
        val sessionId: String? = null
    )

    @Serializable
private data class PageSnapshot(
    val url: String,
    val title: String,
    val userAgent: String,
    val innerWidth: Int,
    val innerHeight: Int,
    val maxTouchPoints: Int
)

@Serializable
private data class StorageSnapshot(
    val localStorageValue: String? = null,
    val cookieString: String = ""
)

    private class CdpSession(
        private val ws: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        private val json: Json
    ) {
        private var nextId = 1
        private var pageSessionId: String? = null

        suspend fun enable() {
            pageSessionId = attachToPageTarget()
            command("Page.enable", sessionId = pageSessionId)
            command("Runtime.enable", sessionId = pageSessionId)
            command("Network.enable", sessionId = pageSessionId)
        }

        suspend fun preparePage(targetUrl: String) {
            command("Page.navigate", buildJsonObject { put("url", targetUrl) }, sessionId = pageSessionId)
            waitForReadyState()
        }

        suspend fun applyMobileOverrides() {
            command(
                "Emulation.setDeviceMetricsOverride",
                buildJsonObject {
                    put("width", 390)
                    put("height", 844)
                    put("deviceScaleFactor", 3)
                    put("mobile", true)
                    put("screenWidth", 390)
                    put("screenHeight", 844)
                },
                sessionId = pageSessionId
            )
            command(
                "Network.setUserAgentOverride",
                buildJsonObject {
                    put(
                        "userAgent",
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    )
                    put("acceptLanguage", "en-US,en;q=0.9")
                    put("platform", "iPhone")
                },
                sessionId = pageSessionId
            )
            command(
                "Emulation.setTouchEmulationEnabled",
                buildJsonObject {
                    put("enabled", true)
                    put("maxTouchPoints", 5)
                },
                sessionId = pageSessionId
            )
        }

        suspend fun evaluate(expression: String): JsonElement? {
            val response = command(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                },
                sessionId = pageSessionId
            )
            return response["result"]?.jsonObject?.get("value")
        }

        private suspend fun waitForReadyState() {
            repeat(30) {
                val state = evaluate("document.readyState")?.jsonPrimitive?.contentOrNull
                if (state == "complete" || state == "interactive") return
                delay(500)
            }
            error("Timed out waiting for page readyState")
        }

        private suspend fun attachToPageTarget(): String {
            val targets = command("Target.getTargets")
            val pageId = targets["targetInfos"]?.jsonArray?.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "page"
            }?.jsonObject?.get("targetId")?.jsonPrimitive?.contentOrNull
                ?: error("No page target exposed by Firecrawl CDP session")
            val attached = command(
                "Target.attachToTarget",
                buildJsonObject {
                    put("targetId", pageId)
                    put("flatten", true)
                }
            )
            return attached["sessionId"]?.jsonPrimitive?.contentOrNull
                ?: error("Target.attachToTarget returned no sessionId")
        }

        private suspend fun command(method: String, params: JsonObject? = null, sessionId: String? = null): JsonObject {
            val id = nextId++
            ws.send(Frame.Text(json.encodeToString(CdpMessage(id = id, method = method, params = params, sessionId = sessionId))))
            return withTimeout<JsonObject>(15_000) {
                var response: JsonObject? = null
                while (response == null) {
                    val frame = ws.incoming.receive()
                    if (frame !is Frame.Text) continue
                    val obj = json.parseToJsonElement(frame.readText()).jsonObject
                    val responseId = obj["id"]?.jsonPrimitive?.intOrNull
                    if (responseId != id) continue
                    val responseSessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
                    if (sessionId != null && responseSessionId != null && responseSessionId != sessionId) continue
                    val error = obj["error"]?.jsonObject
                    if (error != null) {
                        error("CDP command '$method' failed: ${error["message"]?.jsonPrimitive?.contentOrNull ?: error}")
                    }
                    response = obj["result"]?.jsonObject ?: obj
                }
                response ?: error("Timed out waiting for CDP response for '$method'")
            }
        }
    }
}
