package com.suprbeta.browser

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.PushNotificationSender
import com.suprbeta.supabase.NotificationRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class BrowserNotFoundException(message: String) : RuntimeException(message)
class BrowserConflictException(message: String) : RuntimeException(message)
class BrowserValidationException(message: String) : RuntimeException(message)
class BrowserProviderUnavailableException(message: String) : RuntimeException(message)

interface BrowserService {
    suspend fun listProfiles(userId: String): BrowserProfileListResponse
    suspend fun createProfile(userId: String, request: CreateBrowserProfileRequest): BrowserProfileView
    suspend fun resetProfile(userId: String, profileId: String): BrowserProfileView
    suspend fun createSession(userId: String, request: CreateBrowserSessionRequest): BrowserSessionView
    suspend fun getSession(userId: String, sessionId: String): BrowserSessionView
    suspend fun executeSession(userId: String, request: BrowserExecRequest): BrowserExecResponse
    suspend fun requestTakeover(userId: String, sessionId: String, request: TakeoverRequest): BrowserSessionView
    suspend fun resumeSession(userId: String, sessionId: String, request: ResumeBrowserSessionRequest): BrowserSessionView
    suspend fun closeSession(userId: String, sessionId: String, closingState: String = BrowserSessionState.CLOSED): BrowserSessionView
    suspend fun getViewerPage(userId: String, sessionId: String, interactive: Boolean): String
    suspend fun getAdminSnapshot(): BrowserAdminSnapshot
    fun shutdown()
}

class BrowserServiceImpl(
    private val firestoreRepository: FirestoreRepository,
    private val providerClient: BrowserProviderClient,
    private val notificationRepository: NotificationRepository,
    private val userClientProvider: UserSupabaseClientProvider,
    private val pushNotificationSender: PushNotificationSender,
    private val application: Application,
    private val config: BrowserConfig = BrowserConfig.fromEnvironment(application)
) : BrowserService {

    private val logger = application.log
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()
    private val providerFailures = AtomicInteger(0)
    private val providerCircuitOpenUntil = AtomicLong(0L)
    private val sessionsCreated = AtomicLong(0)
    private val sessionCreateFailures = AtomicLong(0)
    private val takeoverRequests = AtomicLong(0)
    private val resumeSuccesses = AtomicLong(0)
    private val resumeExpirations = AtomicLong(0)
    private val sessionsClosed = AtomicLong(0)
    private val sessionsExpired = AtomicLong(0)
    private val gracefulCloseExpirations = AtomicLong(0)
    private val providerErrors = AtomicLong(0)
    private val keepaliveFailures = AtomicLong(0)
    private val reconciliationRuns = AtomicLong(0)
    private val reconciliationFailures = AtomicLong(0)
    private val lastProviderError = AtomicReference<String?>(null)
    private val lastReconciliationAt = AtomicReference<String?>(null)
    private val lastReconciliationError = AtomicReference<String?>(null)

    companion object {
        private const val PROVIDER = "firecrawl"
        private const val PROVIDER_FAILURE_THRESHOLD = 3
        private const val PROVIDER_CIRCUIT_OPEN_MS = 60_000L
    }

    init {
        application.monitor.subscribe(io.ktor.server.application.ApplicationStarted) {
            if (!config.enabled) return@subscribe
            scope.launch {
                delay(Random.nextLong(1_000, 5_000))
                while (true) {
                    runCatching { reconcileNow() }
                        .onFailure { logger.error("Cloud browser reconciliation failed", it) }
                    delay(config.reconciliationIntervalSeconds * 1_000L + Random.nextLong(0, 5_000))
                }
            }
        }
    }

    override suspend fun listProfiles(userId: String): BrowserProfileListResponse {
        val profiles = firestoreRepository.listBrowserProfiles(userId)
            .sortedByDescending { it.lastUsedAt ?: it.createdAt }
            .map { it.toView() }
        return BrowserProfileListResponse(count = profiles.size, profiles = profiles)
    }

    override suspend fun createProfile(userId: String, request: CreateBrowserProfileRequest): BrowserProfileView {
        val label = request.label.trim()
        if (label.isBlank()) throw BrowserValidationException("label is required")

        val now = Instant.now().toString()
        val profileId = "browser_profile_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val profile = BrowserProfileInternal(
            id = profileId,
            userId = userId,
            label = label,
            provider = PROVIDER,
            providerProfileName = buildProviderProfileName(userId, profileId, generation = 1),
            generation = 1,
            status = "active",
            createdAt = now,
            lastUsedAt = now
        )
        firestoreRepository.saveBrowserProfile(userId, profile)
        return profile.toView()
    }

    override suspend fun resetProfile(userId: String, profileId: String): BrowserProfileView {
        val now = Instant.now().toString()
        val current = firestoreRepository.getBrowserProfile(userId, profileId)
            ?: throw BrowserNotFoundException("Browser profile '$profileId' not found")
        if (!current.lockedBySessionId.isNullOrBlank()) {
            throw BrowserConflictException("Browser profile '$profileId' is in use")
        }
        val nextGeneration = current.generation + 1
        val rotated = firestoreRepository.rotateBrowserProfile(
            userId = userId,
            profileId = profileId,
            newProviderProfileName = buildProviderProfileName(userId, profileId, nextGeneration),
            nowIso = now
        ) ?: throw BrowserNotFoundException("Browser profile '$profileId' not found")
        return rotated.toView()
    }

    override suspend fun createSession(userId: String, request: CreateBrowserSessionRequest): BrowserSessionView {
        ensureBrowserEnabled()
        ensureProviderCircuitOpen()
        val profile = firestoreRepository.getBrowserProfile(userId, request.profileId)
            ?: throw BrowserNotFoundException("Browser profile '${request.profileId}' not found")
        val droplet = requireUserDroplet(userId)
        ensureCapacity(userId)

        val now = Instant.now()
        val takeoverTimeoutSeconds = config.normalizedTakeoverTimeout(request.takeoverTimeoutSeconds)
        val ttlSeconds = config.defaultTtlSeconds
        val activityTtlSeconds = config.activityTtlSeconds(takeoverTimeoutSeconds)
        val sessionId = "browser_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val viewerUrl = "${config.publicBaseUrl}/api/browser/sessions/$sessionId/view"
        val takeoverUrl = "${config.publicBaseUrl}/api/browser/sessions/$sessionId/takeover"
        val lockExpiresAt = now.plusSeconds((takeoverTimeoutSeconds + config.gracefulCloseMarginSeconds).toLong())
        val stub = BrowserSessionInternal(
            id = sessionId,
            userId = userId,
            taskId = request.taskId?.trim()?.ifBlank { null },
            profileId = profile.id,
            state = BrowserSessionState.CREATING,
            viewerUrl = viewerUrl,
            takeoverUrl = takeoverUrl,
            initialUrl = request.initialUrl?.trim()?.ifBlank { null },
            createdAt = now.toString(),
            updatedAt = now.toString(),
            lastHeartbeatAt = now.toString(),
            expiresAt = now.plusSeconds(ttlSeconds.toLong()).toString(),
            activityExpiresAt = now.plusSeconds(activityTtlSeconds.toLong()).toString(),
            gracefulCloseDeadlineAt = now.plusSeconds((ttlSeconds - config.gracefulCloseMarginSeconds).toLong()).toString(),
            takeoverDeadlineAt = now.plusSeconds(takeoverTimeoutSeconds.toLong()).toString()
        )

        firestoreRepository.acquireBrowserProfileLockAndCreateSession(
            userId = userId,
            profileId = profile.id,
            session = stub,
            lockExpiresAtIso = lockExpiresAt.toString(),
            nowIso = now.toString(),
            staleHeartbeatSeconds = config.staleHeartbeatSeconds.toLong()
        )

        var providerSession: ProviderBrowserSession? = null
        try {
            providerSession = withProviderCall {
                providerClient.createSession(profile.providerProfileName, stub.initialUrl, ttlSeconds, activityTtlSeconds)
            }
            withProviderCall {
                providerClient.applyMobileEmulation(providerSession.cdpUrl)
            }

            val activated = stub.copy(
                providerSessionId = providerSession.id,
                providerLiveViewUrl = providerSession.liveViewUrl,
                providerInteractiveLiveViewUrl = providerSession.interactiveLiveViewUrl,
                providerCdpUrl = providerSession.cdpUrl,
                state = BrowserSessionState.ACTIVE,
                updatedAt = Instant.now().toString(),
                expiresAt = providerSession.expiresAt.ifBlank { stub.expiresAt }
            ).normalizeDeadlines(config.gracefulCloseMarginSeconds)

            firestoreRepository.saveBrowserSession(activated)
            firestoreRepository.heartbeatBrowserProfile(userId, profile.id, Instant.now().toString())
            startHeartbeat(activated)
            announceBrowserActivity(activated, droplet, type = "browser.activity.started", title = "Browser Activity Started")
            sessionsCreated.incrementAndGet()
            return activated.toView()
        } catch (e: Exception) {
            sessionCreateFailures.incrementAndGet()
            providerSession?.let {
                runCatching { withProviderCall { providerClient.deleteSession(it.id) } }
            }
            val failed = stub.copy(
                state = BrowserSessionState.FAILED,
                updatedAt = Instant.now().toString(),
                lastError = e.message ?: "Browser session creation failed"
            )
            firestoreRepository.saveBrowserSession(failed)
            firestoreRepository.releaseBrowserProfileLock(userId, profile.id, sessionId)
            throw e
        }
    }

    override suspend fun getSession(userId: String, sessionId: String): BrowserSessionView =
        getOwnedSession(userId, sessionId).normalizeExpired().also {
            if (it.state == BrowserSessionState.EXPIRED && it.state != getOwnedSession(userId, sessionId).state) {
                firestoreRepository.saveBrowserSession(it)
            }
        }.toView()

    override suspend fun executeSession(userId: String, request: BrowserExecRequest): BrowserExecResponse {
        val session = getOwnedSession(userId, request.sessionId).normalizeExpired()
        if (session.state !in BrowserSessionState.liveStates) {
            throw BrowserConflictException("Browser session '${request.sessionId}' is not active")
        }
        if (request.code.isBlank()) {
            throw BrowserValidationException("code is required")
        }

        val language = request.language.trim().ifBlank { "bash" }
        val normalizedCode = normalizeExecCode(request.code, language)
        val execution = withProviderCall {
            providerClient.executeSession(
                providerSessionId = session.providerSessionId,
                code = normalizedCode,
                language = language,
                timeoutSeconds = request.timeoutSeconds
            )
        }
        val inspection = inspectSessionState(session.providerSessionId)
        val signals = inferSignals(normalizedCode, inspection, execution)
        val status = inferExecStatus(signals, execution)
        return BrowserExecResponse(
            status = status,
            sessionId = session.id,
            page = BrowserPageInfo(
                url = inspection?.url,
                title = inspection?.title,
                viewportMode = "mobile"
            ),
            signals = signals,
            summary = BrowserSummary(
                visibleText = inspection?.visibleText ?: execution.stdout?.take(4000) ?: execution.result?.take(4000),
                primaryActions = inspection?.primaryActions ?: emptyList()
            ),
            artifacts = BrowserArtifacts(),
            execution = BrowserExecutionOutput(
                language = language,
                stdout = execution.stdout,
                stderr = execution.stderr,
                result = execution.result,
                exitCode = execution.exitCode,
                killed = execution.killed
            ),
            error = execution.error?.let {
                BrowserExecError(
                    code = if (status == "retryable_error") "provider_retryable" else "provider_error",
                    message = it
                )
            },
            retryAfterSeconds = null
        )
    }

    override suspend fun requestTakeover(userId: String, sessionId: String, request: TakeoverRequest): BrowserSessionView {
        val session = getOwnedSession(userId, sessionId)
        val updated = session.copy(
            state = BrowserSessionState.TAKEOVER_REQUESTED,
            updatedAt = Instant.now().toString(),
            lastError = request.reason?.trim()?.ifBlank { null }
        )
        firestoreRepository.saveBrowserSession(updated)
        val droplet = requireUserDroplet(userId)
        announceBrowserActivity(updated, droplet, type = "browser.takeover.requested", title = "Browser Needs Your Attention")
        startHeartbeat(updated)
        takeoverRequests.incrementAndGet()
        return updated.toView()
    }

    override suspend fun resumeSession(userId: String, sessionId: String, request: ResumeBrowserSessionRequest): BrowserSessionView {
        val session = getOwnedSession(userId, sessionId)
        if (session.state !in setOf(BrowserSessionState.TAKEOVER_REQUESTED, BrowserSessionState.USER_ACTIVE, BrowserSessionState.AGENT_RESUMING)) {
            throw BrowserConflictException("Browser session '$sessionId' is not waiting for takeover")
        }
        val providerStillLive = withProviderCall {
            providerClient.listActiveSessions().any { it.id == session.providerSessionId }
        }
        if (!providerStillLive) {
            val expired = session.copy(
                state = BrowserSessionState.EXPIRED,
                updatedAt = Instant.now().toString(),
                lastError = "Provider session expired before resume"
            )
            firestoreRepository.saveBrowserSession(expired)
            firestoreRepository.releaseBrowserProfileLock(userId, session.profileId, session.id)
            stopHeartbeat(session.id)
            resumeExpirations.incrementAndGet()
            sessionsExpired.incrementAndGet()
            return expired.toView()
        }
        val resumed = session.copy(
            state = BrowserSessionState.ACTIVE,
            updatedAt = Instant.now().toString(),
            lastError = request.note?.trim()?.ifBlank { null } ?: session.lastError
        )
        firestoreRepository.saveBrowserSession(resumed)
        startHeartbeat(resumed)
        resumeSuccesses.incrementAndGet()
        return resumed.toView()
    }

    override suspend fun closeSession(userId: String, sessionId: String, closingState: String): BrowserSessionView {
        val session = getOwnedSession(userId, sessionId)
        return closeSessionInternal(session, closingState).toView()
    }

    override suspend fun getViewerPage(userId: String, sessionId: String, interactive: Boolean): String {
        val session = getOwnedSession(userId, sessionId).normalizeExpired()
        if (session.state == BrowserSessionState.EXPIRED || session.state == BrowserSessionState.CLOSED || session.state == BrowserSessionState.FAILED) {
            throw BrowserConflictException("Browser session '$sessionId' is no longer available")
        }

        val pageSession = if (interactive) {
            if (session.state !in setOf(BrowserSessionState.TAKEOVER_REQUESTED, BrowserSessionState.USER_ACTIVE, BrowserSessionState.AGENT_RESUMING)) {
                throw BrowserConflictException("Browser session '$sessionId' is not available for takeover")
            }
            val updated = session.copy(
                state = BrowserSessionState.USER_ACTIVE,
                updatedAt = Instant.now().toString()
            )
            firestoreRepository.saveBrowserSession(updated)
            startHeartbeat(updated)
            updated
        } else {
            session
        }

        val providerUrl = if (interactive) pageSession.providerInteractiveLiveViewUrl else pageSession.providerLiveViewUrl
        if (providerUrl.isBlank()) throw BrowserConflictException("Browser session '$sessionId' viewer is unavailable")

        return buildViewerHtml(providerUrl, interactive)
    }

    override suspend fun getAdminSnapshot(): BrowserAdminSnapshot {
        val openUntilMillis = providerCircuitOpenUntil.get()
        return BrowserAdminSnapshot(
            capturedAtUtc = Instant.now().toString(),
            enabled = config.enabled,
            provider = PROVIDER,
            activeSessionCount = firestoreRepository.countActiveBrowserSessions(),
            globalActiveSessionLimit = config.globalActiveSessionLimit,
            perUserActiveSessionLimit = config.perUserActiveSessionLimit,
            heartbeatJobCount = heartbeatJobs.size,
            circuitOpen = openUntilMillis > System.currentTimeMillis(),
            circuitOpenUntilUtc = openUntilMillis.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() },
            consecutiveProviderFailures = providerFailures.get(),
            lastProviderError = lastProviderError.get(),
            lastReconciliationAtUtc = lastReconciliationAt.get(),
            lastReconciliationError = lastReconciliationError.get(),
            counters = BrowserAdminCounters(
                sessionsCreated = sessionsCreated.get(),
                sessionCreateFailures = sessionCreateFailures.get(),
                takeoverRequests = takeoverRequests.get(),
                resumeSuccesses = resumeSuccesses.get(),
                resumeExpirations = resumeExpirations.get(),
                sessionsClosed = sessionsClosed.get(),
                sessionsExpired = sessionsExpired.get(),
                gracefulCloseExpirations = gracefulCloseExpirations.get(),
                providerErrors = providerErrors.get(),
                keepaliveFailures = keepaliveFailures.get(),
                reconciliationRuns = reconciliationRuns.get(),
                reconciliationFailures = reconciliationFailures.get()
            )
        )
    }

    internal suspend fun reconcileNow() {
        reconciliationRuns.incrementAndGet()
        lastReconciliationAt.set(Instant.now().toString())
        try {
            reconcileSessions()
            lastReconciliationError.set(null)
        } catch (e: Exception) {
            reconciliationFailures.incrementAndGet()
            lastReconciliationError.set(e.message ?: e::class.simpleName ?: "Unknown reconciliation error")
            throw e
        }
    }

    override fun shutdown() {
        heartbeatJobs.values.forEach { it.cancel() }
        scope.cancel()
    }

    private fun ensureBrowserEnabled() {
        if (!config.enabled) throw BrowserFeatureDisabledException()
        if (config.firecrawlApiKey.isBlank()) throw BrowserProviderUnavailableException("FIRECRAWL_API_KEY is not configured")
    }

    private fun ensureProviderCircuitOpen() {
        val openUntil = providerCircuitOpenUntil.get()
        if (openUntil > System.currentTimeMillis()) {
            throw BrowserProviderUnavailableException("Cloud browser provider is temporarily unavailable")
        }
    }

    private suspend fun requireUserDroplet(userId: String): UserDropletInternal {
        return firestoreRepository.getUserDropletInternal(userId)
            ?: throw BrowserNotFoundException("No droplet found for user")
    }

    private suspend fun ensureCapacity(userId: String) {
        val global = firestoreRepository.countActiveBrowserSessions()
        if (global >= config.globalActiveSessionLimit) {
            throw BrowserCapacityException(config.retryAfterSeconds(), "Cloud browser capacity is full")
        }
        val perUser = firestoreRepository.countActiveBrowserSessions(userId)
        if (perUser >= config.perUserActiveSessionLimit) {
            throw BrowserCapacityException(config.retryAfterSeconds(), "User browser session limit reached")
        }
    }

    private suspend fun getOwnedSession(userId: String, sessionId: String): BrowserSessionInternal {
        val session = firestoreRepository.getBrowserSessionInternal(sessionId)
            ?: throw BrowserNotFoundException("Browser session '$sessionId' not found")
        if (session.userId != userId) throw BrowserNotFoundException("Browser session '$sessionId' not found")
        return session
    }

    private suspend fun closeSessionInternal(session: BrowserSessionInternal, closingState: String): BrowserSessionInternal {
        stopHeartbeat(session.id)
        if (session.providerSessionId.isNotBlank()) {
            runCatching { withProviderCall { providerClient.deleteSession(session.providerSessionId) } }
                .onFailure { logger.warn("Failed to delete provider browser session ${session.providerSessionId}: ${it.message}") }
        }
        val closed = session.copy(
            state = closingState,
            updatedAt = Instant.now().toString()
        )
        firestoreRepository.saveBrowserSession(closed)
        firestoreRepository.releaseBrowserProfileLock(session.userId, session.profileId, session.id)
        when (closingState) {
            BrowserSessionState.CLOSED -> sessionsClosed.incrementAndGet()
            BrowserSessionState.EXPIRED -> {
                sessionsExpired.incrementAndGet()
                if (session.lastError == "Graceful close deadline reached") {
                    gracefulCloseExpirations.incrementAndGet()
                }
            }
        }
        return closed
    }

    private suspend fun announceBrowserActivity(
        session: BrowserSessionInternal,
        droplet: UserDropletInternal,
        type: String,
        title: String
    ) {
        val client = userClientProvider.getClient(
            droplet.resolveSupabaseUrl(),
            droplet.supabaseServiceKey,
            droplet.supabaseSchema
        )
        val payload = buildJsonObject {
            put("body", "Browser activity is available for task ${session.taskId ?: "session ${session.id}"}")
            put("sessionId", session.id)
            put("viewerUrl", session.viewerUrl)
            put("takeoverUrl", session.takeoverUrl)
            session.taskId?.let { put("taskId", it) }
            put("state", session.state)
            put("type", type)
        }

        runCatching {
            notificationRepository.createNotification(client, type = type, payload = payload)
        }.onFailure {
            logger.warn("Failed to persist browser notification for session ${session.id}: ${it.message}")
        }

        val fcmToken = firestoreRepository.getFcmToken(session.userId)
        if (!fcmToken.isNullOrBlank()) {
            pushNotificationSender.sendNotification(
                fcmToken = fcmToken,
                title = title,
                body = "Open the live browser viewer in SuprClaw.",
                data = mapOf(
                    "browserSessionId" to session.id,
                    "viewerUrl" to session.viewerUrl,
                    "takeoverUrl" to session.takeoverUrl,
                    "browserState" to session.state,
                    "browserEventType" to type
                ) + (session.taskId?.let { mapOf("taskId" to it) } ?: emptyMap()),
                highPriority = true
            )
        }
    }

    private fun startHeartbeat(session: BrowserSessionInternal) {
        if (session.state !in BrowserSessionState.liveStates) {
            stopHeartbeat(session.id)
            return
        }

        heartbeatJobs.compute(session.id) { _, existing ->
            existing?.cancel()
            scope.launch {
                while (true) {
                    delay(config.heartbeatIntervalSeconds * 1_000L)
                    val latest = firestoreRepository.getBrowserSessionInternal(session.id) ?: break
                    if (latest.state !in BrowserSessionState.liveStates) break
                    val nowIso = Instant.now().toString()
                    firestoreRepository.heartbeatBrowserSession(latest.id, nowIso)
                    firestoreRepository.heartbeatBrowserProfile(latest.userId, latest.profileId, nowIso)
                    if (latest.state == BrowserSessionState.USER_ACTIVE || latest.state == BrowserSessionState.TAKEOVER_REQUESTED) {
                        runCatching { withProviderCall { providerClient.sendKeepalive(latest.providerCdpUrl) } }
                            .onFailure {
                                keepaliveFailures.incrementAndGet()
                                logger.warn("Browser keepalive failed for session ${latest.id}: ${it.message}")
                            }
                    }
                }
            }
        }
    }

    private fun stopHeartbeat(sessionId: String) {
        heartbeatJobs.remove(sessionId)?.cancel()
    }

    private suspend fun reconcileSessions() {
        val sessions = firestoreRepository.listBrowserSessionsByStates(BrowserSessionState.liveStates)
        val providerSessionIds = runCatching { withProviderCall { providerClient.listActiveSessions().associateBy { it.id } } }
            .getOrElse {
                logger.warn("Skipping browser reconciliation provider check: ${it.message}")
                return
            }
        val now = Instant.now()
        for (session in sessions) {
            val normalized = session.normalizeExpired()
            if (normalized.state == BrowserSessionState.EXPIRED) {
                closeSessionInternal(normalized, BrowserSessionState.EXPIRED)
                continue
            }

            val deadline = runCatching { Instant.parse(normalized.gracefulCloseDeadlineAt) }.getOrNull()
            if (deadline != null && !deadline.isAfter(now)) {
                closeSessionInternal(normalized.copy(lastError = "Graceful close deadline reached"), BrowserSessionState.EXPIRED)
                continue
            }

            val heartbeat = runCatching { Instant.parse(normalized.lastHeartbeatAt ?: normalized.updatedAt) }.getOrNull()
            if (heartbeat != null && heartbeat.plusSeconds(config.staleHeartbeatSeconds.toLong()).isBefore(now)) {
                val live = normalized.providerSessionId.isNotBlank() && providerSessionIds.containsKey(normalized.providerSessionId)
                if (!live) {
                    firestoreRepository.releaseBrowserProfileLock(normalized.userId, normalized.profileId, normalized.id)
                    val failed = normalized.copy(
                        state = BrowserSessionState.FAILED,
                        updatedAt = now.toString(),
                        lastError = "Browser session heartbeat expired"
                    )
                    firestoreRepository.saveBrowserSession(failed)
                    stopHeartbeat(normalized.id)
                }
            }
        }
    }

    private suspend fun <T> withProviderCall(block: suspend () -> T): T {
        ensureProviderCircuitOpen()
        return try {
            val result = block()
            providerFailures.set(0)
            providerCircuitOpenUntil.set(0L)
            lastProviderError.set(null)
            result
        } catch (e: Exception) {
            providerErrors.incrementAndGet()
            lastProviderError.set(e.message ?: e::class.simpleName ?: "Unknown provider error")
            val failures = providerFailures.incrementAndGet()
            if (failures >= PROVIDER_FAILURE_THRESHOLD) {
                providerCircuitOpenUntil.set(System.currentTimeMillis() + PROVIDER_CIRCUIT_OPEN_MS)
            }
            throw e
        }
    }

    private suspend fun inspectSessionState(providerSessionId: String): BrowserInspection? {
        val inspectionExecution = runCatching {
            withProviderCall {
                providerClient.executeSession(
                    providerSessionId = providerSessionId,
                    language = "node",
                    timeoutSeconds = 30,
                    code = """
                        const snapshot = await page.evaluate(() => {
                          const text = (document.body?.innerText || "").replace(/\s+/g, " ").trim().slice(0, 4000);
                          const actions = Array.from(
                            document.querySelectorAll('button, a, input[type="submit"], input[type="button"], [role="button"]')
                          )
                            .map((el) => (el.innerText || el.value || el.getAttribute('aria-label') || '').trim())
                            .filter(Boolean)
                            .slice(0, 12);
                          return {
                            url: location.href,
                            title: document.title,
                            visibleText: text,
                            primaryActions: actions
                          };
                        });
                        console.log(JSON.stringify(snapshot));
                    """.trimIndent()
                )
            }
        }.getOrNull() ?: return null

        return parseInspection(inspectionExecution.stdout, inspectionExecution.result, inspectionExecution.raw)
    }

    private fun normalizeExecCode(code: String, language: String): String {
        if (!language.equals("bash", ignoreCase = true)) return code
        val trimmed = code.trim()
        if (trimmed.startsWith("agent-browser ")) return trimmed
        return "agent-browser $trimmed"
    }

    private fun inferSignals(
        code: String,
        inspection: BrowserInspection?,
        execution: ProviderBrowserExecution
    ): BrowserSignals {
        val signalText = buildString {
            appendLine(inspection?.visibleText.orEmpty())
            appendLine(execution.stdout.orEmpty())
            appendLine(execution.stderr.orEmpty())
            appendLine(execution.error.orEmpty())
        }.lowercase()

        return BrowserSignals(
            captchaDetected = listOf("captcha", "recaptcha", "hcaptcha", "verify you are human", "cloudflare").any { it in signalText },
            mfaDetected = listOf("two-factor", "two factor", "verification code", "authentication code", "one-time passcode", "otp").any { it in signalText },
            loginDetected = listOf("sign in", "log in", "password", "email address").count { it in signalText } >= 2,
            fileDownloadAttempted = code.contains("download", ignoreCase = true) || signalText.contains("download")
        )
    }

    private fun inferExecStatus(signals: BrowserSignals, execution: ProviderBrowserExecution): String {
        if (signals.captchaDetected || signals.mfaDetected) return "takeover_required"
        if (execution.killed || execution.error.orEmpty().contains("timeout", ignoreCase = true)) return "retryable_error"
        if (!execution.success) return "fatal_error"
        if ((execution.exitCode ?: 0) != 0) return "fatal_error"
        if (!execution.stderr.isNullOrBlank() && execution.stdout.isNullOrBlank() && execution.result.isNullOrBlank()) return "fatal_error"
        return "ok"
    }

    private fun parseInspection(vararg candidates: String?): BrowserInspection? {
        val decoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            val lines = candidate.lines().map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }.reversed()
            for (line in lines) {
                runCatching { decoder.decodeFromString(BrowserInspection.serializer(), line) }.getOrNull()?.let { return it }
            }
            val trimmed = candidate.trim()
            runCatching { decoder.decodeFromString(BrowserInspection.serializer(), trimmed) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun buildProviderProfileName(userId: String, profileId: String, generation: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$userId:$profileId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "sc:prod:$digest:$generation"
    }

    private fun buildViewerHtml(providerUrl: String, interactive: Boolean): String {
        val mode = if (interactive) "Takeover" else "Live View"
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>SuprClaw Browser $mode</title>
              <style>
                html, body { margin: 0; padding: 0; height: 100%; background: #0b1020; color: #f5f7ff; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
                header { padding: 12px 16px; background: rgba(9, 14, 28, 0.92); font-size: 14px; letter-spacing: 0.03em; }
                iframe { width: 100%; height: calc(100% - 45px); border: 0; background: #111827; }
              </style>
            </head>
            <body>
              <header>SuprClaw Browser $mode</header>
              <iframe src="$providerUrl" allow="clipboard-read; clipboard-write"></iframe>
            </body>
            </html>
        """.trimIndent()
    }
}

private fun BrowserSessionInternal.normalizeExpired(): BrowserSessionInternal {
    val expiresAtInstant = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return this
    if (!expiresAtInstant.isAfter(Instant.now())) {
        return copy(
            state = BrowserSessionState.EXPIRED,
            updatedAt = Instant.now().toString(),
            lastError = lastError ?: "Browser session expired"
        )
    }
    return this
}

private fun BrowserSessionInternal.normalizeDeadlines(gracefulCloseMarginSeconds: Int): BrowserSessionInternal {
    val expiry = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return this
    val activity = runCatching { Instant.parse(activityExpiresAt) }.getOrNull() ?: expiry
    val graceful = expiry.minusSeconds(gracefulCloseMarginSeconds.toLong()).coerceAtLeast(activity.minusSeconds(30))
    return copy(gracefulCloseDeadlineAt = graceful.toString())
}

private fun Instant.coerceAtLeast(other: Instant): Instant = if (isBefore(other)) other else this

@kotlinx.serialization.Serializable
private data class BrowserInspection(
    val url: String? = null,
    val title: String? = null,
    val visibleText: String? = null,
    val primaryActions: List<String> = emptyList()
)
