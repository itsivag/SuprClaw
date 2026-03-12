package com.suprbeta.browser

import kotlinx.serialization.Serializable

object BrowserSessionState {
    const val CREATING = "creating"
    const val ACTIVE = "active"
    const val TAKEOVER_REQUESTED = "takeover_requested"
    const val USER_ACTIVE = "user_active"
    const val AGENT_RESUMING = "agent_resuming"
    const val CLOSED = "closed"
    const val EXPIRED = "expired"
    const val FAILED = "failed"
    const val CAPACITY_BLOCKED = "capacity_blocked"

    val liveStates = setOf(CREATING, ACTIVE, TAKEOVER_REQUESTED, USER_ACTIVE, AGENT_RESUMING)
}

@Serializable
data class BrowserProfileInternal(
    val id: String = "",
    val userId: String = "",
    val label: String = "",
    val provider: String = "firecrawl",
    val providerProfileName: String = "",
    val generation: Int = 1,
    val status: String = "active",
    val lockedBySessionId: String? = null,
    val lockedAt: String? = null,
    val lockExpiresAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val createdAt: String = "",
    val lastUsedAt: String? = null,
    val tombstonedAt: String? = null,
    val retiredProviderProfileNames: List<String> = emptyList()
) {
    constructor() : this("", "", "", "firecrawl", "", 1, "active", null, null, null, null, "", null, null, emptyList())
}

@Serializable
data class BrowserProfileView(
    val id: String,
    val label: String,
    val generation: Int,
    val status: String,
    val createdAt: String,
    val lastUsedAt: String? = null
)

@Serializable
data class BrowserProfileListResponse(
    val count: Int,
    val profiles: List<BrowserProfileView>
)

@Serializable
data class CreateBrowserProfileRequest(
    val label: String
)

@Serializable
data class BrowserSessionInternal(
    val id: String = "",
    val userId: String = "",
    val taskId: String? = null,
    val profileId: String = "",
    val providerSessionId: String = "",
    val state: String = BrowserSessionState.CREATING,
    val viewerUrl: String = "",
    val takeoverUrl: String = "",
    val initialUrl: String? = null,
    val providerLiveViewUrl: String = "",
    val providerInteractiveLiveViewUrl: String = "",
    val providerCdpUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastHeartbeatAt: String? = null,
    val expiresAt: String = "",
    val activityExpiresAt: String = "",
    val gracefulCloseDeadlineAt: String = "",
    val takeoverDeadlineAt: String? = null,
    val lastError: String? = null,
    val retryAfterSeconds: Int? = null
) {
    constructor() : this(
        id = "",
        userId = "",
        taskId = null,
        profileId = "",
        providerSessionId = "",
        state = BrowserSessionState.CREATING,
        viewerUrl = "",
        takeoverUrl = "",
        initialUrl = null,
        providerLiveViewUrl = "",
        providerInteractiveLiveViewUrl = "",
        providerCdpUrl = "",
        createdAt = "",
        updatedAt = "",
        lastHeartbeatAt = null,
        expiresAt = "",
        activityExpiresAt = "",
        gracefulCloseDeadlineAt = "",
        takeoverDeadlineAt = null,
        lastError = null,
        retryAfterSeconds = null
    )
}

@Serializable
data class BrowserSessionView(
    val sessionId: String,
    val taskId: String? = null,
    val profileId: String,
    val state: String,
    val viewerUrl: String,
    val takeoverUrl: String,
    val initialUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String,
    val activityExpiresAt: String,
    val gracefulCloseDeadlineAt: String,
    val takeoverDeadlineAt: String? = null,
    val lastError: String? = null,
    val retryAfterSeconds: Int? = null
)

@Serializable
data class BrowserSessionEventPayload(
    val browserSessionId: String,
    val viewerUrl: String,
    val takeoverUrl: String,
    val browserState: String,
    val browserEventType: String,
    val taskId: String? = null
)

@Serializable
data class CreateBrowserSessionRequest(
    val profileId: String,
    val taskId: String? = null,
    val initialUrl: String? = null,
    val takeoverTimeoutSeconds: Int? = null
)

@Serializable
data class TakeoverRequest(
    val reason: String? = null
)

@Serializable
data class ResumeBrowserSessionRequest(
    val note: String? = null
)

@Serializable
data class BrowserExecRequest(
    val sessionId: String,
    val code: String,
    val language: String = "bash",
    val timeoutSeconds: Int? = null
)

@Serializable
data class BrowserPageInfo(
    val url: String? = null,
    val title: String? = null,
    val viewportMode: String = "desktop"
)

@Serializable
data class BrowserSignals(
    val captchaDetected: Boolean = false,
    val mfaDetected: Boolean = false,
    val loginDetected: Boolean = false,
    val fileDownloadAttempted: Boolean = false
)

@Serializable
data class BrowserSummary(
    val visibleText: String? = null,
    val primaryActions: List<String> = emptyList()
)

@Serializable
data class BrowserArtifacts(
    val screenshotRef: String? = null
)

@Serializable
data class BrowserExecutionOutput(
    val language: String,
    val stdout: String? = null,
    val stderr: String? = null,
    val result: String? = null,
    val exitCode: Int? = null,
    val killed: Boolean = false
)

@Serializable
data class BrowserExecError(
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class BrowserExecResponse(
    val status: String,
    val sessionId: String,
    val page: BrowserPageInfo = BrowserPageInfo(),
    val signals: BrowserSignals = BrowserSignals(),
    val summary: BrowserSummary = BrowserSummary(),
    val artifacts: BrowserArtifacts = BrowserArtifacts(),
    val execution: BrowserExecutionOutput? = null,
    val error: BrowserExecError? = null,
    val retryAfterSeconds: Int? = null
)

@Serializable
data class BrowserAdminCounters(
    val sessionsCreated: Long,
    val sessionCreateFailures: Long,
    val takeoverRequests: Long,
    val resumeSuccesses: Long,
    val resumeExpirations: Long,
    val sessionsClosed: Long,
    val sessionsExpired: Long,
    val gracefulCloseExpirations: Long,
    val providerErrors: Long,
    val keepaliveFailures: Long,
    val reconciliationRuns: Long,
    val reconciliationFailures: Long
)

@Serializable
data class BrowserAdminSnapshot(
    val capturedAtUtc: String,
    val enabled: Boolean,
    val provider: String,
    val activeSessionCount: Int,
    val globalActiveSessionLimit: Int,
    val perUserActiveSessionLimit: Int,
    val heartbeatJobCount: Int,
    val circuitOpen: Boolean,
    val circuitOpenUntilUtc: String? = null,
    val consecutiveProviderFailures: Int,
    val lastProviderError: String? = null,
    val lastReconciliationAtUtc: String? = null,
    val lastReconciliationError: String? = null,
    val counters: BrowserAdminCounters
)

fun BrowserProfileInternal.toView(): BrowserProfileView = BrowserProfileView(
    id = id,
    label = label,
    generation = generation,
    status = status,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt
)

fun BrowserSessionInternal.toView(): BrowserSessionView = BrowserSessionView(
    sessionId = id,
    taskId = taskId,
    profileId = profileId,
    state = state,
    viewerUrl = viewerUrl,
    takeoverUrl = takeoverUrl,
    initialUrl = initialUrl,
    createdAt = createdAt,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
    activityExpiresAt = activityExpiresAt,
    gracefulCloseDeadlineAt = gracefulCloseDeadlineAt,
    takeoverDeadlineAt = takeoverDeadlineAt,
    lastError = lastError,
    retryAfterSeconds = retryAfterSeconds
)

fun BrowserSessionInternal.toEventPayload(
    browserEventType: String,
    taskIdOverride: String? = taskId
): BrowserSessionEventPayload = BrowserSessionEventPayload(
    browserSessionId = id,
    viewerUrl = viewerUrl,
    takeoverUrl = takeoverUrl,
    browserState = state,
    browserEventType = browserEventType,
    taskId = taskIdOverride
)
