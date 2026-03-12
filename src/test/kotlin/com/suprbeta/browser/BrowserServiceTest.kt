package com.suprbeta.browser

import com.suprbeta.digitalocean.models.UserDropletInternal
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.firebase.PushNotificationSender
import com.suprbeta.supabase.NotificationRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowserServiceTest {
    private val firestoreRepository = mockk<FirestoreRepository>()
    private val providerClient = mockk<BrowserProviderClient>()
    private val notificationRepository = mockk<NotificationRepository>(relaxed = true)
    private val userClientProvider = mockk<UserSupabaseClientProvider>()
    private val pushNotificationSender = mockk<PushNotificationSender>(relaxed = true)
    private val clientBridge = mockk<BrowserClientBridge>(relaxed = true)
    private val supabaseClient = mockk<SupabaseClient>(relaxed = true)

    private val profile = BrowserProfileInternal(
        id = "profile-1",
        userId = "user-1",
        label = "Primary",
        provider = "firecrawl",
        providerProfileName = "sc:prod:abc123:1",
        generation = 1,
        status = "active",
        createdAt = "2026-03-12T10:00:00Z",
        lastUsedAt = "2026-03-12T10:00:00Z"
    )

    private val droplet = UserDropletInternal(
        userId = "user-1",
        dropletId = 99L,
        supabaseProjectRef = "project-1",
        supabaseServiceKey = "service-key",
        supabaseSchema = "public"
    )

    private fun browserConfig() = BrowserConfig(
        enabled = true,
        apiBaseUrl = "https://api.firecrawl.dev",
        publicBaseUrl = "https://api.suprclaw.com",
        firecrawlApiKey = "firecrawl-key",
        defaultTtlSeconds = 1800,
        defaultTakeoverTimeoutSeconds = 600,
        minTakeoverTimeoutSeconds = 300,
        maxTakeoverTimeoutSeconds = 1800,
        gracefulCloseMarginSeconds = 120,
        keepaliveIntervalSeconds = 30,
        heartbeatIntervalSeconds = 3600,
        staleHeartbeatSeconds = 45,
        reconciliationIntervalSeconds = 3600,
        globalActiveSessionLimit = 20,
        perUserActiveSessionLimit = 2,
        retryAfterMinSeconds = 30,
        retryAfterMaxSeconds = 30,
        deleteConfirmTimeoutSeconds = 30,
        deleteConfirmPollSeconds = 2
    )

    private fun newService(application: Application): BrowserServiceImpl {
        every { userClientProvider.getClient(any(), any(), any()) } returns supabaseClient
        every { clientBridge.resolveTaskId(any()) } returns null
        return BrowserServiceImpl(
            firestoreRepository = firestoreRepository,
            providerClient = providerClient,
            notificationRepository = notificationRepository,
            userClientProvider = userClientProvider,
            pushNotificationSender = pushNotificationSender,
            application = application,
            config = browserConfig(),
            clientBridge = clientBridge
        )
    }

    @Test
    fun `create session acquires lock then creates provider session and announces browser activity`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val lockedSession = slot<BrowserSessionInternal>()
            val savedSession = slot<BrowserSessionInternal>()
            val publishedEvent = slot<BrowserSessionEventPayload>()

            coEvery { firestoreRepository.getBrowserProfile("user-1", "profile-1") } returns profile
            coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
            coEvery { firestoreRepository.countActiveBrowserSessions(null) } returns 0
            coEvery { firestoreRepository.countActiveBrowserSessions("user-1") } returns 0
            every { clientBridge.resolveTaskId("user-1") } returns "agent:main:main"
            coEvery {
                firestoreRepository.acquireBrowserProfileLockAndCreateSession(
                    userId = "user-1",
                    profileId = "profile-1",
                    session = capture(lockedSession),
                    lockExpiresAtIso = any(),
                    nowIso = any(),
                    staleHeartbeatSeconds = 45
                )
            } returns Unit
            coEvery {
                providerClient.createSession(
                    profileName = "sc:prod:abc123:1",
                    initialUrl = null,
                    ttlSeconds = 1800,
                    activityTtlSeconds = 1800
                )
            } returns ProviderBrowserSession(
                id = "provider-1",
                cdpUrl = "wss://cdp.example.com/1",
                liveViewUrl = "https://live.example.com/1",
                interactiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                expiresAt = "2026-03-12T10:30:00Z"
            )
            coEvery { providerClient.applyMobileEmulation("wss://cdp.example.com/1") } returns Unit
            coEvery { providerClient.navigateToUrl("wss://cdp.example.com/1", "https://example.com") } returns Unit
            coEvery { firestoreRepository.saveBrowserSession(capture(savedSession)) } returns Unit
            coEvery { firestoreRepository.heartbeatBrowserProfile("user-1", "profile-1", any()) } returns Unit
            coEvery { firestoreRepository.getFcmToken("user-1") } returns "fcm-token"
            coEvery { notificationRepository.createNotification(any(), any(), any(), any()) } returns Unit
            coEvery { pushNotificationSender.sendNotification(any(), any(), any(), any(), any()) } returns Unit

            val result = kotlinx.coroutines.runBlocking {
                service.createSession(
                    "user-1",
                    CreateBrowserSessionRequest(
                        profileId = "profile-1",
                        initialUrl = "https://example.com",
                        takeoverTimeoutSeconds = 600
                    )
                )
            }

            assertEquals(BrowserSessionState.ACTIVE, result.state)
            assertTrue(result.viewerUrl.endsWith("/view"))
            assertTrue(result.takeoverUrl.endsWith("/takeover"))
            assertEquals("agent:main:main", result.taskId)
            assertEquals(BrowserSessionState.CREATING, lockedSession.captured.state)
            assertEquals(BrowserSessionState.ACTIVE, savedSession.captured.state)
            assertEquals("provider-1", savedSession.captured.providerSessionId)
            assertEquals("agent:main:main", lockedSession.captured.taskId)

            coVerifyOrder {
                firestoreRepository.acquireBrowserProfileLockAndCreateSession(
                    "user-1", "profile-1", any(), any(), any(), 45
                )
                providerClient.createSession("sc:prod:abc123:1", null, 1800, 1800)
                providerClient.applyMobileEmulation("wss://cdp.example.com/1")
                providerClient.navigateToUrl("wss://cdp.example.com/1", "https://example.com")
                firestoreRepository.saveBrowserSession(any())
            }
            coVerify {
                notificationRepository.createNotification(
                    supabaseClient,
                    "browser.activity.started",
                    match { payload ->
                        payload["sessionId"]?.toString()?.contains(savedSession.captured.id) == true &&
                            payload["viewerUrl"]?.toString()?.contains("/view") == true &&
                            payload["taskId"]?.toString()?.contains("agent:main:main") == true &&
                            payload["browserEventType"]?.toString()?.contains("browser.session.created") == true
                    },
                    null
                )
            }
            coVerify {
                clientBridge.publishEvent("user-1", capture(publishedEvent))
            }
            assertEquals("browser.session.created", publishedEvent.captured.browserEventType)
            assertEquals("agent:main:main", publishedEvent.captured.taskId)
            coVerify(exactly = 0) {
                pushNotificationSender.sendNotification(
                    any(),
                    "Browser Activity Started",
                    any(),
                    any(),
                    any()
                )
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `create session returns capacity blocked before lock or provider calls`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            coEvery { firestoreRepository.getBrowserProfile("user-1", "profile-1") } returns profile
            coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
            coEvery { firestoreRepository.countActiveBrowserSessions(null) } returns 20

            val error = assertFailsWith<BrowserCapacityException> {
                kotlinx.coroutines.runBlocking {
                    service.createSession("user-1", CreateBrowserSessionRequest(profileId = "profile-1"))
                }
            }

            assertEquals(30, error.retryAfterSeconds)
            coVerify(exactly = 0) { firestoreRepository.acquireBrowserProfileLockAndCreateSession(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { providerClient.createSession(any(), any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `create session provider failure persists failed session and releases lock`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val failedSession = slot<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserProfile("user-1", "profile-1") } returns profile
            coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
            coEvery { firestoreRepository.countActiveBrowserSessions(null) } returns 0
            coEvery { firestoreRepository.countActiveBrowserSessions("user-1") } returns 0
            coEvery { firestoreRepository.acquireBrowserProfileLockAndCreateSession(any(), any(), any(), any(), any(), any()) } returns Unit
            coEvery { providerClient.createSession(any(), any(), any(), any()) } throws BrowserProviderException("provider create failed")
            coEvery { firestoreRepository.saveBrowserSession(capture(failedSession)) } returns Unit
            coEvery { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", any()) } returns Unit

            val error = assertFailsWith<BrowserProviderException> {
                kotlinx.coroutines.runBlocking {
                    service.createSession("user-1", CreateBrowserSessionRequest(profileId = "profile-1"))
                }
            }

            assertEquals("provider create failed", error.message)
            assertEquals(BrowserSessionState.FAILED, failedSession.captured.state)
            coVerify(exactly = 1) { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", any()) }
            coVerify(exactly = 0) { notificationRepository.createNotification(any(), any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `request takeover persists takeover state without sending fcm push`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val activeSession = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val updatedSession = slot<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns activeSession
            coEvery { firestoreRepository.saveBrowserSession(capture(updatedSession)) } returns Unit
            coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
            coEvery { firestoreRepository.getFcmToken("user-1") } returns "fcm-token"
            coEvery { notificationRepository.createNotification(any(), any(), any(), any()) } returns Unit
            coEvery { pushNotificationSender.sendNotification(any(), any(), any(), any(), any()) } returns Unit

            val result = kotlinx.coroutines.runBlocking {
                service.requestTakeover("user-1", "browser-1", TakeoverRequest("captcha"))
            }

            assertEquals(BrowserSessionState.TAKEOVER_REQUESTED, result.state)
            assertEquals(BrowserSessionState.TAKEOVER_REQUESTED, updatedSession.captured.state)
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.takeover_requested" &&
                            it.browserState == BrowserSessionState.TAKEOVER_REQUESTED &&
                            it.taskId == "task-1"
                    }
                )
            }
            coVerify(exactly = 0) { pushNotificationSender.sendNotification(any(), any(), any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `resume session returns active when provider session still exists`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.TAKEOVER_REQUESTED,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val saved = mutableListOf<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session
            coEvery { providerClient.listActiveSessions() } returns listOf(
                ProviderBrowserSessionSummary(id = "provider-1", status = "active")
            )
            coEvery { firestoreRepository.saveBrowserSession(capture(saved)) } returns Unit

            val result = kotlinx.coroutines.runBlocking {
                service.resumeSession("user-1", "browser-1", ResumeBrowserSessionRequest("done"))
            }

            assertEquals(BrowserSessionState.ACTIVE, result.state)
            assertEquals(listOf(BrowserSessionState.AGENT_RESUMING, BrowserSessionState.ACTIVE), saved.map { it.state })
            assertEquals("done", saved.last().lastError)
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.agent_resuming" &&
                            it.browserState == BrowserSessionState.AGENT_RESUMING
                    }
                )
            }
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.active" &&
                            it.browserState == BrowserSessionState.ACTIVE
                    }
                )
            }
            coVerify(exactly = 0) { firestoreRepository.releaseBrowserProfileLock(any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `resume session expires and releases lock when provider session is gone`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.TAKEOVER_REQUESTED,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val saved = mutableListOf<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session
            coEvery { providerClient.listActiveSessions() } returns emptyList()
            coEvery { firestoreRepository.saveBrowserSession(capture(saved)) } returns Unit
            coEvery { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-1") } returns Unit

            val result = kotlinx.coroutines.runBlocking {
                service.resumeSession("user-1", "browser-1", ResumeBrowserSessionRequest())
            }

            assertEquals(BrowserSessionState.EXPIRED, result.state)
            assertEquals(listOf(BrowserSessionState.AGENT_RESUMING, BrowserSessionState.EXPIRED), saved.map { it.state })
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.expired" &&
                            it.browserState == BrowserSessionState.EXPIRED
                    }
                )
            }
            coVerify(exactly = 1) { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-1") }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `execute session returns takeover required when captcha is detected`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session
            coEvery {
                providerClient.executeSession("provider-1", "agent-browser snapshot", "bash", 60)
            } returns ProviderBrowserExecution(
                success = true,
                stdout = "CAPTCHA required",
                result = null,
                exitCode = 0
            )
            coEvery {
                providerClient.executeSession("provider-1", match { it.contains("console.log(JSON.stringify(snapshot))") }, "node", 30)
            } returns ProviderBrowserExecution(
                success = true,
                stdout = """{"url":"https://example.com","title":"Verify","visibleText":"Please solve CAPTCHA to continue","primaryActions":["Continue"]}""",
                exitCode = 0
            )

            val result = kotlinx.coroutines.runBlocking {
                service.executeSession(
                    "user-1",
                    BrowserExecRequest(
                        sessionId = "browser-1",
                        code = "snapshot",
                        language = "bash",
                        timeoutSeconds = 60
                    )
                )
            }

            assertEquals("takeover_required", result.status)
            assertTrue(result.signals.captchaDetected)
            assertEquals("https://example.com", result.page.url)
            assertEquals("bash", result.execution?.language)
            coVerify(exactly = 1) { providerClient.executeSession("provider-1", "agent-browser snapshot", "bash", 60) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `execute session rejects non bash languages`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z"
            )

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session

            val error = assertFailsWith<BrowserValidationException> {
                kotlinx.coroutines.runBlocking {
                    service.executeSession(
                        "user-1",
                        BrowserExecRequest(
                            sessionId = "browser-1",
                            code = "console.log('hello')",
                            language = "node",
                            timeoutSeconds = 45
                        )
                    )
                }
            }

            assertEquals("Only bash agent-browser commands are allowed", error.message)
            coVerify(exactly = 0) { providerClient.executeSession(any(), any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `execute session blocks sensitive browser commands until takeover`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z"
            )

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session

            val error = assertFailsWith<BrowserConflictException> {
                kotlinx.coroutines.runBlocking {
                    service.executeSession(
                        "user-1",
                        BrowserExecRequest(
                            sessionId = "browser-1",
                            code = "type '#password' hunter2",
                            language = "bash",
                            timeoutSeconds = 45
                        )
                    )
                }
            }

            assertEquals("Sensitive browser action requires takeover", error.message)
            coVerify(exactly = 0) { providerClient.executeSession(any(), any(), any(), any()) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `viewer page uses same origin launch path instead of provider url`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://liveview.firecrawl.dev/opaque",
                providerInteractiveLiveViewUrl = "https://liveview.firecrawl.dev/opaque?interactive=true",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z"
            )

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session

            val html = kotlinx.coroutines.runBlocking {
                service.getViewerPage("user-1", "browser-1", interactive = false)
            }
            val launchUrl = kotlinx.coroutines.runBlocking {
                service.getViewerLaunchUrl("user-1", "browser-1", interactive = false)
            }

            assertTrue(html.contains("/api/browser/sessions/browser-1/view/launch"))
            assertTrue(html.contains("viewport-fit=cover"))
            assertTrue(!html.contains("<header>"))
            assertTrue(!html.contains("https://liveview.firecrawl.dev/opaque"))
            assertEquals("https://liveview.firecrawl.dev/opaque", launchUrl)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `interactive viewer marks session user active and publishes browser payload`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "agent:main:main",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.TAKEOVER_REQUESTED,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://liveview.firecrawl.dev/opaque",
                providerInteractiveLiveViewUrl = "https://liveview.firecrawl.dev/opaque?interactive=true",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val saved = slot<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session
            coEvery { firestoreRepository.saveBrowserSession(capture(saved)) } returns Unit

            val html = kotlinx.coroutines.runBlocking {
                service.getViewerPage("user-1", "browser-1", interactive = true)
            }

            assertTrue(html.contains("/api/browser/sessions/browser-1/takeover/launch"))
            assertEquals(BrowserSessionState.USER_ACTIVE, saved.captured.state)
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.user_active" &&
                            it.browserState == BrowserSessionState.USER_ACTIVE &&
                            it.taskId == "agent:main:main"
                    }
                )
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `viewer launch rejects unexpected hosts`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "javascript:alert(1)",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z"
            )

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session

            val error = assertFailsWith<BrowserConflictException> {
                kotlinx.coroutines.runBlocking {
                    service.getViewerLaunchUrl("user-1", "browser-1", interactive = false)
                }
            }

            assertEquals("Browser viewer target is invalid", error.message)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `close session deletes provider session and releases profile lock`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val session = BrowserSessionInternal(
                id = "browser-1",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-1/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val saved = slot<BrowserSessionInternal>()

            coEvery { firestoreRepository.getBrowserSessionInternal("browser-1") } returns session
            coEvery { providerClient.deleteSession("provider-1") } returns Unit
            coEvery { firestoreRepository.saveBrowserSession(capture(saved)) } returns Unit
            coEvery { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-1") } returns Unit

            val result = kotlinx.coroutines.runBlocking {
                service.closeSession("user-1", "browser-1")
            }

            assertEquals(BrowserSessionState.CLOSED, result.state)
            assertEquals(BrowserSessionState.CLOSED, saved.captured.state)
            coVerifyOrder {
                providerClient.deleteSession("provider-1")
                firestoreRepository.saveBrowserSession(any())
                firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-1")
            }
            coVerify {
                clientBridge.publishEvent(
                    "user-1",
                    match {
                        it.browserEventType == "browser.session.closed" &&
                            it.browserState == BrowserSessionState.CLOSED &&
                            it.taskId == "task-1"
                    }
                )
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `provider circuit opens after repeated provider failures and snapshot reflects counters`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            coEvery { firestoreRepository.getBrowserProfile("user-1", "profile-1") } returns profile
            coEvery { firestoreRepository.getUserDropletInternal("user-1") } returns droplet
            coEvery { firestoreRepository.countActiveBrowserSessions(null) } returns 0
            coEvery { firestoreRepository.countActiveBrowserSessions("user-1") } returns 0
            coEvery { firestoreRepository.acquireBrowserProfileLockAndCreateSession(any(), any(), any(), any(), any(), any()) } returns Unit
            coEvery { providerClient.createSession(any(), any(), any(), any()) } throws BrowserProviderException("provider create failed")
            coEvery { firestoreRepository.saveBrowserSession(any()) } returns Unit
            coEvery { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", any()) } returns Unit

            repeat(3) {
                assertFailsWith<BrowserProviderException> {
                    kotlinx.coroutines.runBlocking {
                        service.createSession("user-1", CreateBrowserSessionRequest(profileId = "profile-1"))
                    }
                }
            }

            val unavailable = assertFailsWith<BrowserProviderUnavailableException> {
                kotlinx.coroutines.runBlocking {
                    service.createSession("user-1", CreateBrowserSessionRequest(profileId = "profile-1"))
                }
            }

            assertTrue(unavailable.message?.contains("temporarily unavailable") == true)
            coVerify(exactly = 3) { providerClient.createSession(any(), any(), any(), any()) }

            val snapshot = kotlinx.coroutines.runBlocking { service.getAdminSnapshot() }
            assertTrue(snapshot.circuitOpen)
            assertEquals(3, snapshot.counters.sessionCreateFailures)
            assertEquals(3, snapshot.counters.providerErrors)
            assertEquals(0, snapshot.counters.sessionsCreated)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `reconcileNow marks stale providerless session failed and updates snapshot`() = testApplication {
        lateinit var service: BrowserServiceImpl
        application { service = newService(this) }
        startApplication()
        try {
            val staleSession = BrowserSessionInternal(
                id = "browser-stale",
                userId = "user-1",
                taskId = "task-1",
                profileId = "profile-1",
                providerSessionId = "provider-1",
                state = BrowserSessionState.ACTIVE,
                viewerUrl = "https://api.suprclaw.com/api/browser/sessions/browser-stale/view",
                takeoverUrl = "https://api.suprclaw.com/api/browser/sessions/browser-stale/takeover",
                providerLiveViewUrl = "https://live.example.com/1",
                providerInteractiveLiveViewUrl = "https://live.example.com/1?interactive=true",
                providerCdpUrl = "wss://cdp.example.com/1",
                createdAt = "2026-03-12T10:00:00Z",
                updatedAt = "2026-03-12T10:00:00Z",
                lastHeartbeatAt = "2000-01-01T00:00:00Z",
                expiresAt = "2099-03-12T10:30:00Z",
                activityExpiresAt = "2099-03-12T10:20:00Z",
                gracefulCloseDeadlineAt = "2099-03-12T10:28:00Z",
                takeoverDeadlineAt = "2099-03-12T10:10:00Z"
            )
            val saved = slot<BrowserSessionInternal>()

            coEvery { firestoreRepository.listBrowserSessionsByStates(BrowserSessionState.liveStates) } returns listOf(staleSession)
            coEvery { providerClient.listActiveSessions() } returns emptyList()
            coEvery { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-stale") } returns Unit
            coEvery { firestoreRepository.saveBrowserSession(capture(saved)) } returns Unit
            coEvery { firestoreRepository.countActiveBrowserSessions(null) } returns 0

            kotlinx.coroutines.runBlocking { service.reconcileNow() }

            assertEquals(BrowserSessionState.FAILED, saved.captured.state)
            assertEquals("Browser session heartbeat expired", saved.captured.lastError)
            coVerify(exactly = 1) { firestoreRepository.releaseBrowserProfileLock("user-1", "profile-1", "browser-stale") }

            val snapshot = kotlinx.coroutines.runBlocking { service.getAdminSnapshot() }
            assertTrue(snapshot.counters.reconciliationRuns >= 1)
            assertEquals(0, snapshot.counters.reconciliationFailures)
            assertEquals("firecrawl", snapshot.provider)
        } finally {
            service.shutdown()
        }
    }
}
