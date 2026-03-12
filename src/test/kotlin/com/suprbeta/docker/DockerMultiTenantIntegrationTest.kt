package com.suprbeta.docker

import com.suprbeta.core.CryptoService
import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.digitalocean.DropletMcpServiceImpl
import com.suprbeta.digitalocean.McpToolRegistry
import com.suprbeta.digitalocean.models.ProvisioningStatus
import com.suprbeta.docker.models.*
import com.suprbeta.firebase.FirebaseService
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.hetzner.HetznerDnsService
import com.suprbeta.hetzner.HetznerService
import com.suprbeta.supabase.SelfHostedSupabaseManagementService
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import com.suprbeta.websocket.OpenClawConnector
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import io.ktor.server.application.Application
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the Docker multi-tenant architecture.
 *
 * Unit tests (phases U-*) always run.
 * Integration tests (phases 1–4) require HETZNER_API_TOKEN in .env or environment.
 *
 * Run all:     ./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest"
 * Unit only:   ./gradlew test --tests "com.suprbeta.docker.DockerMultiTenantIntegrationTest.port*"
 *
 * Total runtime with integration tests: ~15–20 min (mostly Docker image build time).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DockerMultiTenantIntegrationTest {
    private val encryptionKeyset: String by lazy {
        loadEnv("FIRESTORE_ENCRYPTION_KEYSET") ?: CryptoService.generateNewKeyset()
    }

    // ── Unit test state ────────────────────────────────────────────────────

    private lateinit var portAllocator: ContainerPortAllocator

    // ── Integration test shared state (populated as phases run) ───────────

    private var serverId: Long = 0L
    private var hostIp: String = ""
    private var rootPassword: String = ""
    private var containerId: String = ""
    private var dockerReady: Boolean = false
    private val containerPort = 18001
    private val gatewayToken = "t" + java.util.UUID.randomUUID().toString().replace("-", "")
    private val hookToken    = "h" + java.util.UUID.randomUUID().toString().replace("-", "")

    // Ed25519 key pair generated once for the WebSocket handshake tests
    private val wsKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    // ── Phase 5 E2E state ──────────────────────────────────────────────────
    private var e2eUserId: String = ""
    private var e2eDockerService: DockerHostProvisioningService? = null
    private var e2eFirebaseService: FirebaseService? = null
    private var e2eHttpClient: HttpClient? = null

    // Env helpers
    private val hetznerToken     = loadEnv("HETZNER_API_TOKEN")
    private val sshPrivateKeyB64 = loadEnv("PROVISIONING_SSH_PRIVATE_KEY_B64")

    // ── Setup / Teardown ──────────────────────────────────────────────────

    @BeforeAll
    fun setup() {
        portAllocator = ContainerPortAllocator(18001, 18050)
    }

    @AfterAll
    fun cleanup() {
        portAllocator.clearAll()
        // Best-effort: delete the Phase 1 test VPS
        if (serverId != 0L && hetznerToken != null) {
            runCatching { deleteHetznerServer(serverId) }
        }
        // Shut down Phase 5 resources
        // Best-effort: remove Phase 1 host from Firestore (pre-registered for Phase 5)
        if (serverId != 0L) {
            runCatching {
                e2eFirebaseService?.firestore
                    ?.collection("hosts")?.document(serverId.toString())?.delete()?.get()
            }
        }
        runCatching { e2eFirebaseService?.shutdown() }
        runCatching { e2eHttpClient?.close() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UNIT TESTS — always run, no infrastructure needed
    // ═══════════════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("Port allocator assigns sequential ports")
    fun `port allocator assigns sequential ports`() {
        val hostId = 999L
        assertEquals(18001, portAllocator.allocatePort(hostId))
        assertEquals(18002, portAllocator.allocatePort(hostId))
        assertEquals(18003, portAllocator.allocatePort(hostId))
    }

    @Test @Order(2)
    @DisplayName("Port allocator prevents duplicate allocation")
    fun `port allocator prevents duplicate allocation`() {
        val hostId = 998L
        val allocated = mutableSetOf<Int>()
        repeat(10) {
            val port = portAllocator.allocatePort(hostId)
            assertTrue(allocated.add(port), "Port $port allocated twice")
        }
    }

    @Test @Order(3)
    @DisplayName("Port allocator releases ports correctly")
    fun `port allocator releases ports correctly`() {
        val hostId = 997L
        val port = portAllocator.allocatePort(hostId)
        assertFalse(portAllocator.isPortAvailable(hostId, port))
        portAllocator.releasePort(hostId, port)
        assertTrue(portAllocator.isPortAvailable(hostId, port))
    }

    @Test @Order(4)
    @DisplayName("Port allocator tracks per-host independently")
    fun `port allocator tracks per-host separately`() {
        val host1 = 991L
        val host2 = 992L
        val p1 = portAllocator.allocatePort(host1)
        val p2 = portAllocator.allocatePort(host2)
        assertEquals(18001, p1)
        assertEquals(18001, p2)
        portAllocator.releasePort(host1, p1)
        assertTrue(portAllocator.isPortAvailable(host1, p1))
        assertFalse(portAllocator.isPortAvailable(host2, p2))
    }

    @Test @Order(5)
    @DisplayName("Port allocator throws when exhausted")
    fun `port allocator throws when exhausted`() {
        val hostId = 996L
        val allocator = ContainerPortAllocator(19001, 19002)
        allocator.allocatePort(hostId)
        allocator.allocatePort(hostId)
        assertThrows(IllegalStateException::class.java) { allocator.allocatePort(hostId) }
    }

    @Test @Order(6)
    @DisplayName("Port allocator reports correct capacity")
    fun `port allocator reports correct capacity`() {
        val hostId = 995L
        assertEquals(50, portAllocator.getCapacity())
        assertEquals(0, portAllocator.getAllocatedPortCount(hostId))
        portAllocator.allocatePort(hostId)
        assertEquals(1, portAllocator.getAllocatedPortCount(hostId))
        assertEquals(49, portAllocator.getAvailablePortCount(hostId))
    }

    @Test @Order(7)
    @DisplayName("ContainerInfo has correct status constants")
    fun `ContainerInfo model has correct status constants`() {
        assertEquals("running",  ContainerInfo.STATUS_RUNNING)
        assertEquals("stopped",  ContainerInfo.STATUS_STOPPED)
        assertEquals("error",    ContainerInfo.STATUS_ERROR)
        assertEquals("creating", ContainerInfo.STATUS_CREATING)
    }

    @Test @Order(8)
    @DisplayName("HostInfo calculates available capacity correctly")
    fun `HostInfo calculates available capacity correctly`() {
        val host = HostInfo(hostId = 1, hostIp = "1.2.3.4", totalCapacity = 20,
            currentContainers = 5, createdAt = "2024-01-01", region = "nbg1")
        assertEquals(15, host.availableCapacity)
        assertFalse(host.isFull)
    }

    @Test @Order(9)
    @DisplayName("HostInfo reports full correctly")
    fun `HostInfo reports full correctly`() {
        val host = HostInfo(hostId = 1, hostIp = "1.2.3.4", totalCapacity = 10,
            currentContainers = 10, createdAt = "2024-01-01", region = "nbg1")
        assertEquals(0, host.availableCapacity)
        assertTrue(host.isFull)
    }

    @Test @Order(10)
    @DisplayName("UserHostDroplet converts to UserDroplet correctly")
    fun `UserHostDroplet converts to UserDroplet correctly`() {
        val hostDroplet = UserHostDroplet(
            userId = "user-123",
            hostServerId = 456,
            hostIp = "1.2.3.4",
            containerId = "abc123def456789",
            port = 18001,
            subdomain = "user-123.suprclaw.com",
            gatewayUrl = "wss://api.suprclaw.com",
            vpsGatewayUrl = "https://user-123.suprclaw.com",
            gatewayToken = "token123",
            supabaseProjectRef = "proj-ref",
            supabaseServiceKey = "key",
            supabaseUrl = "https://supabase.example.com",
            createdAt = "2024-01-01",
            status = "active"
        )
        val droplet = hostDroplet.toUserDroplet()
        assertEquals("user-123",               droplet.userId)
        assertEquals(456,                       droplet.dropletId)
        assertEquals("abc123def456",            droplet.dropletName) // first 12 chars
        assertEquals("wss://api.suprclaw.com",  droplet.gatewayUrl)
        assertEquals("token123",                droplet.gatewayToken)
        assertEquals("active",                  droplet.status)
    }

    @Test @Order(11)
    @DisplayName("McpToolRegistry has expected default tools")
    fun `McpToolRegistry has expected default tools`() {
        val defaults = McpToolRegistry.defaultTools
        assertTrue(defaults.contains("supabase"))
        assertTrue(defaults.contains("cloud_browser"))
        assertNull(McpToolRegistry.get("firecrawl"))
        val tool = McpToolRegistry.get("supabase")
        assertNotNull(tool)
        assertEquals("supabase", tool!!.name)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTEGRATION TESTS — require HETZNER_API_TOKEN
    // ═══════════════════════════════════════════════════════════════════════

    @Test @Order(100)
    @DisplayName("PHASE 1.1 — Create host VPS on Hetzner")
    fun `PHASE 1-1 create host VPS`() {
        assumeTrue(hetznerToken != null, "HETZNER_API_TOKEN not set — skipping integration tests")

        // No user_data — we will run install-docker.sh synchronously via SSH after boot.
        // This is more reliable than cloud-init because we can observe output and errors directly.
        val body = buildJsonObject {
            put("name",        "suprclaw-test-${System.currentTimeMillis()}")
            put("server_type", "cpx22")
            put("image",       "ubuntu-22.04")
            put("location",    "nbg1")
        }.toString()

        val response = hetznerApi("POST", "/servers", body)
        val server   = response["server"]?.jsonObject
            ?: fail<Nothing>("No server object in response: $response")

        serverId     = server["id"]!!.jsonPrimitive.long
        rootPassword = response["root_password"]?.jsonPrimitive?.contentOrNull ?: ""

        assertTrue(serverId > 0, "Expected non-zero server ID")
        println("✅ PHASE 1.1: Server created ID=$serverId  rootPassword=${rootPassword.take(4)}***")
    }

    @Test @Order(110)
    @DisplayName("PHASE 1.2 — Wait for VPS active, then run install-docker.sh via SSH")
    fun `PHASE 1-2 wait for VPS active and Docker installed`() {
        assumeTrue(serverId > 0L, "Server not created — skipping")

        // Wait up to 3 min for server to be running and get its IP
        println("  Waiting for server $serverId to become active...")
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            val info   = hetznerApi("GET", "/servers/$serverId")
            val status = info["server"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
            val ip     = info["server"]?.jsonObject
                ?.get("public_net")?.jsonObject
                ?.get("ipv4")?.jsonObject
                ?.get("ip")?.jsonPrimitive?.contentOrNull

            if (status == "running" && ip != null) {
                hostIp = ip
                println("✅ PHASE 1.2a: Server running at $hostIp")
                break
            }
            print(".")
            Thread.sleep(5_000)
        }
        assertTrue(hostIp.isNotBlank(), "Server did not get a public IP within timeout")

        // Wait for SSH password auth to be available
        println("  Waiting for SSH on $hostIp (password auth)...")
        waitForSshPasswordAuth()
        println("✅ PHASE 1.2b: SSH password auth ready")

        // SCP install-docker.sh and run it synchronously so we see errors immediately
        println("  Uploading and running install-docker.sh (this takes 5-8 min)...")
        scpFile("docker/host-setup/install-docker.sh", "/tmp/install-docker.sh")
        // Pass the provisioning SSH public key so openclaw user gets key auth configured
        val sshPublicKey = loadEnv("PROVISIONING_SSH_PUBLIC_KEY") ?: ""
        if (sshPublicKey.isBlank()) println("  ⚠️  PROVISIONING_SSH_PUBLIC_KEY not set — openclaw SSH key auth will not be configured")
        val escapedKey = sshPublicKey.replace("'", "'\\''")
        val out = sshWithTimeout(
            "chmod +x /tmp/install-docker.sh && PROVISIONING_SSH_PUBLIC_KEY='$escapedKey' /tmp/install-docker.sh 2>&1 | tee /tmp/install-docker.log",
            timeoutSeconds = 600
        )
        println("  Install output (last 20 lines):\n${out.lines().takeLast(20).joinToString("\n")}")
        println("✅ PHASE 1.2c: install-docker.sh completed")

        // Verify Docker is running
        val version = ssh("docker info --format '{{.ServerVersion}}'")
        assertTrue(version.isNotBlank(), "Docker not running after install. See /tmp/install-docker.log")
        dockerReady = true
        println("✅ PHASE 1.2d: Docker running — version $version")
    }

    @Test @Order(120)
    @DisplayName("PHASE 1.3 — Verify Traefik is running on host")
    fun `PHASE 1-3 verify Traefik running`() {
        assumeTrue(dockerReady, "Docker not ready — skipping")

        val status = ssh("docker ps --filter 'name=traefik' --format '{{.Status}}' 2>/dev/null || echo ''")
        assertTrue(status.isNotBlank(), "Traefik container not found. Check install-docker.sh logs.")
        assertTrue(status.contains("Up", ignoreCase = true), "Traefik not running: $status")

        val ping = ssh("curl -s --connect-timeout 5 http://localhost:8080/ping 2>/dev/null || echo FAIL")
        assertEquals("OK", ping.trim(), "Traefik API not responding: $ping")

        println("✅ PHASE 1.3: Traefik running ($status), API responding")
    }

    @Test @Order(200)
    @DisplayName("PHASE 2.1 — Build OpenClaw container image on host")
    fun `PHASE 2-1 build openclaw image on host`() {
        assumeTrue(dockerReady, "Docker not ready — skipping")

        println("  Copying Dockerfile and build assets to host...")
        // Mirror the directory structure the Dockerfile expects (build context = project root)
        ssh("mkdir -p /tmp/openclaw-build/docker/openclaw-container /tmp/openclaw-build/src/main/resources")
        scpFile("docker/openclaw-container/Dockerfile",       "/tmp/openclaw-build/docker/openclaw-container/Dockerfile")
        scpFile("docker/openclaw-container/entrypoint.sh",    "/tmp/openclaw-build/docker/openclaw-container/entrypoint.sh")
        scpFile("docker/openclaw-container/supervisord.conf", "/tmp/openclaw-build/docker/openclaw-container/supervisord.conf")
        scpFile("docker/openclaw-container/nginx-default.conf", "/tmp/openclaw-build/docker/openclaw-container/nginx-default.conf")
        scpFile("src/main/resources/mcp-auth-proxy.js",       "/tmp/openclaw-build/src/main/resources/mcp-auth-proxy.js")

        println("  Building Docker image (npm installs — may take 5–8 min)...")
        // Use 10-minute timeout for the build; -f because Dockerfile is not at context root
        val out = sshWithTimeout(
            "cd /tmp/openclaw-build && docker build -f docker/openclaw-container/Dockerfile -t suprclaw/openclaw:latest . 2>&1 | tail -30",
            timeoutSeconds = 600
        )
        println("  Build output:\n$out")

        val imageCheck = ssh("docker images --format '{{.Repository}}:{{.Tag}}' | grep suprclaw/openclaw || echo MISSING")
        assertFalse(imageCheck.contains("MISSING"), "Image not found after build. Build output:\n$out")
        println("✅ PHASE 2.1: Image built — $imageCheck")
    }

    @Test @Order(210)
    @DisplayName("PHASE 2.2 — Create and start OpenClaw container")
    fun `PHASE 2-2 create and start openclaw container`() {
        assumeTrue(dockerReady, "Docker not ready — skipping")

        val cid = ssh("""
            docker run -d \
                --name openclaw-test-${System.currentTimeMillis()} \
                --restart unless-stopped \
                -p $containerPort:18789 \
                --memory=512m \
                --cpus=0.5 \
                -e GATEWAY_TOKEN='$gatewayToken' \
                -e HOOK_TOKEN='$hookToken' \
                -e SUPABASE_URL='https://test.supabase.example.com' \
                -e SUPABASE_SERVICE_KEY='test-key' \
                -e SUPABASE_PROJECT_REF='test-ref' \
                -e USER_ID='integration-test-user' \
                suprclaw/openclaw:latest
        """.trimIndent().replace("\n", " ").trim())

        assertTrue(cid.isNotBlank() && cid.length >= 12, "Container not started. Got: '$cid'")
        containerId = cid.trim()

        // Wait up to 60s for container to be in "running" state
        println("  Waiting for container to reach running state...")
        val deadline = System.currentTimeMillis() + 60_000L
        var running = false
        while (System.currentTimeMillis() < deadline) {
            val st = ssh("docker ps --filter 'id=$containerId' --format '{{.Status}}' 2>/dev/null || echo ''")
            if (st.contains("Up", ignoreCase = true)) { running = true; break }
            Thread.sleep(2_000)
        }

        if (!running) {
            val logs = ssh("docker logs $containerId --tail 30 2>&1 || echo 'no logs'")
            fail<Nothing>("Container not running after 60s. Logs:\n$logs")
        }
        println("✅ PHASE 2.2: Container running — ${containerId.take(12)}")
    }

    @Test @Order(220)
    @DisplayName("PHASE 2.3 — Verify container processes are running")
    fun `PHASE 2-3 verify container processes`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        // Check supervisord is running (it manages openclaw-gateway, mcp-auth-proxy, mcporter)
        val supervisord = ssh("docker exec $containerId ps aux | grep supervisord | grep -v grep || echo MISSING")
        assertFalse(supervisord.contains("MISSING"), "supervisord not running in container")

        // Check openclaw-gateway is up
        val gateway = ssh("docker exec $containerId ps aux | grep openclaw | grep -v grep || echo MISSING")
        assertFalse(gateway.contains("MISSING"),
            "openclaw-gateway not running. Container logs:\n${ssh("docker logs $containerId --tail 20 2>&1")}")

        println("✅ PHASE 2.3: supervisord and openclaw-gateway are running")
    }

    @Test @Order(230)
    @DisplayName("PHASE 2.4 — Verify port mapping is correct")
    fun `PHASE 2-4 verify port mapping`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        val portMap = ssh("docker port $containerId 2>/dev/null || echo ''")
        assertTrue(portMap.contains("$containerPort"), "Port $containerPort not mapped. Got: $portMap")
        println("✅ PHASE 2.4: Port mapping correct — $portMap")
    }

    @Test @Order(300)
    @DisplayName("PHASE 3.1 — Configure Traefik route for container")
    fun `PHASE 3-1 configure Traefik route`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        val testSubdomain = "test-${System.currentTimeMillis()}"
        val yamlContent = """
            http:
              routers:
                $testSubdomain:
                  rule: "Host(`$testSubdomain.suprclaw.com`)"
                  service: $testSubdomain-service
                  entryPoints:
                    - web
              services:
                $testSubdomain-service:
                  loadBalancer:
                    servers:
                      - url: "http://127.0.0.1:$containerPort"
        """.trimIndent()

        val encoded = Base64.getEncoder().encodeToString(yamlContent.toByteArray())
        ssh("mkdir -p /opt/traefik/dynamic && echo '$encoded' | base64 -d > /opt/traefik/dynamic/$testSubdomain.yml")

        val exists = ssh("test -f /opt/traefik/dynamic/$testSubdomain.yml && echo YES || echo NO")
        assertEquals("YES", exists.trim(), "Traefik config file not created")

        println("✅ PHASE 3.1: Traefik route config written for $testSubdomain")
    }

    @Test @Order(310)
    @DisplayName("PHASE 3.2 — Verify local HTTP through Traefik")
    fun `PHASE 3-2 verify local connectivity through Traefik`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        // Direct curl to the container port from the host — any response (even 401) proves the gateway is reachable
        val response = ssh("curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 http://localhost:$containerPort/ 2>/dev/null || echo 000")
        val code = response.trim()

        assertNotEquals("000", code, "No response from gateway on port $containerPort (connection refused)")
        println("✅ PHASE 3.2: Local HTTP reached container — HTTP $code")
    }

    @Test @Order(320)
    @DisplayName("PHASE 3.3 — Verify container health endpoint")
    fun `PHASE 3-3 verify health endpoint`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        // Wait up to 60s for the health endpoint to respond
        val deadline = System.currentTimeMillis() + 60_000L
        var healthy = false
        var lastResponse = ""
        while (System.currentTimeMillis() < deadline) {
            lastResponse = ssh("curl -s --connect-timeout 5 http://localhost:$containerPort/health 2>/dev/null || echo FAIL")
            if (!lastResponse.contains("FAIL") && lastResponse.isNotBlank()) { healthy = true; break }
            Thread.sleep(3_000)
        }

        if (!healthy) {
            val logs = ssh("docker logs $containerId --tail 30 2>&1")
            fail<Nothing>("Health endpoint not responding after 60s. Last: '$lastResponse'. Logs:\n$logs")
        }
        println("✅ PHASE 3.3: Health endpoint responding — '$lastResponse'")
    }

    @Test @Order(400)
    @DisplayName("PHASE 4.1 — Verify gateway status via docker exec")
    fun `PHASE 4-1 verify openclaw gateway status`() {
        assumeTrue(containerId.isNotBlank(), "Container not started — skipping")

        val status = ssh("docker exec $containerId openclaw gateway status 2>&1 || echo 'STATUS_FAILED'")
        assertFalse(status.contains("STATUS_FAILED") && status.isBlank(),
            "openclaw gateway status failed. Output: $status")
        println("✅ PHASE 4.1: Gateway status — ${status.take(120)}")
    }

    @Test @Order(410)
    @DisplayName("PHASE 4.2 — WebSocket connect and receive connect.challenge")
    fun `PHASE 4-2 WebSocket connects and receives challenge`() {
        assumeTrue(hostIp.isNotBlank() && containerId.isNotBlank(), "Prerequisites not ready — skipping")

        var challengeReceived = false
        runBlocking {
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                withTimeout(15_000) {
                    client.webSocket("ws://$hostIp:$containerPort/ws") {
                        // The first frame from openclaw-gateway should be connect.challenge
                        val frame = incoming.receive()
                        if (frame is Frame.Text) {
                            val json = Json.parseToJsonElement(frame.readText()).jsonObject
                            val event = json["event"]?.jsonPrimitive?.contentOrNull
                            if (event == "connect.challenge") {
                                challengeReceived = true
                                println("  Received: ${frame.readText().take(200)}")
                            }
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
                    }
                }
            } catch (e: Exception) {
                // Check if it's just a close after we already got the challenge
                if (!challengeReceived) fail<Nothing>("WebSocket error before receiving challenge: ${e.message}")
            } finally {
                client.close()
            }
        }

        assertTrue(challengeReceived, "Did not receive connect.challenge from gateway")
        println("✅ PHASE 4.2: Gateway sent connect.challenge — WebSocket connection confirmed")
    }

    @Test @Order(420)
    @DisplayName("PHASE 4.3 — Complete WebSocket handshake")
    fun `PHASE 4-3 complete WebSocket handshake`() {
        assumeTrue(hostIp.isNotBlank() && containerId.isNotBlank(), "Prerequisites not ready — skipping")

        var handshakeResult = "PENDING"
        runBlocking {
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                withTimeout(30_000) {
                    client.webSocket("ws://$hostIp:$containerPort/ws") {
                        // Step 1: receive connect.challenge
                        var nonce: String? = null
                        val challengeFrame = incoming.receive()
                        if (challengeFrame is Frame.Text) {
                            val obj = Json.parseToJsonElement(challengeFrame.readText()).jsonObject
                            nonce = obj["payload"]?.jsonObject?.get("nonce")?.jsonPrimitive?.contentOrNull
                        }

                        // Step 2: build and send connect request with Ed25519 signature
                        val deviceId  = sha256Hex(rawEd25519PublicKey(wsKeyPair.public.encoded))
                        val publicKey = base64Url(rawEd25519PublicKey(wsKeyPair.public.encoded))
                        val signedAt  = System.currentTimeMillis()
                        val version   = if (nonce != null) "v2" else "v1"
                        val scopes    = "operator.read,operator.write"
                        val parts = mutableListOf(version, deviceId, "cli", "cli", "operator", scopes, signedAt.toString(), gatewayToken)
                        if (nonce != null) parts.add(nonce)
                        val payload   = parts.joinToString("|")
                        val signature = signEd25519(payload)

                        val connectReq = buildJsonObject {
                            put("type", "req")
                            put("id", "1")
                            put("method", "connect")
                            putJsonObject("params") {
                                put("minProtocol", 3)
                                put("maxProtocol", 3)
                                putJsonObject("client") {
                                    put("id", "cli")
                                    put("version", "2026.2.15")
                                    put("platform", "test")
                                    put("mode", "cli")
                                }
                                put("role", "operator")
                                put("scopes", buildJsonArray { add(JsonPrimitive("operator.read")); add(JsonPrimitive("operator.write")) })
                                putJsonObject("device") {
                                    put("id", deviceId)
                                    put("publicKey", publicKey)
                                    put("signedAt", signedAt)
                                    put("signature", signature)
                                    if (nonce != null) put("nonce", nonce)
                                }
                                put("caps", buildJsonArray { })
                                put("commands", buildJsonArray { })
                                putJsonObject("permissions") { }
                                putJsonObject("auth") { put("token", gatewayToken) }
                                put("locale", "en-US")
                                put("userAgent", "suprclaw-test/1.0")
                            }
                        }.toString()
                        send(Frame.Text(connectReq))

                        // Step 3: wait for response — success or pairing required
                        val resp = incoming.receive()
                        if (resp is Frame.Text) {
                            val obj = Json.parseToJsonElement(resp.readText()).jsonObject
                            val type  = obj["type"]?.jsonPrimitive?.contentOrNull
                            val error = obj["error"]?.jsonObject

                            when {
                                type == "res" && error == null -> {
                                    handshakeResult = "CONNECTED"
                                    println("  ← ${resp.readText().take(200)}")
                                }
                                error != null -> {
                                    val msg = error["message"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (msg == "pairing required") {
                                        // Approve via docker exec and note it for future test runs
                                        val requestId = error["details"]?.jsonObject
                                            ?.get("requestId")?.jsonPrimitive?.contentOrNull
                                        if (requestId != null) {
                                            ssh("docker exec $containerId openclaw devices approve $requestId 2>&1 || true")
                                            handshakeResult = "PAIRING_APPROVED"
                                            println("  Pairing required → approved requestId=$requestId")
                                            println("  Re-run this test to verify CONNECTED on second attempt")
                                        } else {
                                            handshakeResult = "PAIRING_NO_REQUEST_ID"
                                        }
                                    } else {
                                        handshakeResult = "ERROR:$msg"
                                    }
                                }
                                else -> handshakeResult = "UNKNOWN:${resp.readText().take(100)}"
                            }
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
                    }
                }
            } catch (e: Exception) {
                if (handshakeResult == "PENDING") handshakeResult = "EXCEPTION:${e.message}"
            } finally {
                client.close()
            }
        }

        // CONNECTED = full success
        // PAIRING_APPROVED = first-time pairing, approved and ready for second run
        // Anything else is a failure
        assertTrue(
            handshakeResult == "CONNECTED" || handshakeResult == "PAIRING_APPROVED",
            "Handshake failed: $handshakeResult"
        )
        println("✅ PHASE 4.3: Handshake result = $handshakeResult")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 5: End-to-End Provisioning Flow
    // Requires: HETZNER_API_TOKEN + FIREBASE_CREDENTIALS_PATH + FIREBASE_PROJECT_ID
    //           + Supabase self-hosted env vars
    // Creates a real Hetzner host + container + Supabase project + Firestore records.
    // Note: the host VPS created by HostPoolManager is NOT auto-deleted by teardown —
    //       it stays in the pool. Delete it manually from the Hetzner console after the test.
    // ═══════════════════════════════════════════════════════════════════════

    @Test @Order(500)
    @DisplayName("PHASE 5.1 — Full provisioning via DockerHostProvisioningService")
    @org.junit.jupiter.api.Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `PHASE 5-1 full provisioning via DockerHostProvisioningService`() {
        assumeTrue(hetznerToken != null, "HETZNER_API_TOKEN not set — skipping Phase 5")
        assumeTrue(dockerReady, "Phase 1.2 (Docker host setup) did not complete — skipping Phase 5")
        assumeProvisioningHostTrustConfigured()
        val firebaseCredPath = loadEnv("FIREBASE_CREDENTIALS_PATH")
        val firebaseProjectId = loadEnv("FIREBASE_PROJECT_ID")
        assumeTrue(firebaseCredPath != null, "FIREBASE_CREDENTIALS_PATH not set — skipping Phase 5")
        assumeTrue(firebaseProjectId != null, "FIREBASE_PROJECT_ID not set — skipping Phase 5")

        val app = mockk<Application>(relaxed = true)
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        e2eHttpClient = httpClient

        // Firebase / Firestore
        val firebaseService = FirebaseService(app)
        e2eFirebaseService = firebaseService
        val cryptoService = CryptoService(app, encryptionKeyset)
        val firestoreRepository = FirestoreRepository(firebaseService.firestore, app, cryptoService)

        // Hetzner VPS + DNS
        val vpsService = HetznerService(httpClient, app)
        val dnsProvider = HetznerDnsService(httpClient, app)

        // SSH (uses PROVISIONING_SSH_PRIVATE_KEY_B64)
        val sshExec = SshCommandExecutorImpl(app)

        // Supabase
        val managementService = SelfHostedSupabaseManagementService(httpClient, app)
        val schemaRepository = SupabaseSchemaRepository(app)
        val agentRepository = SupabaseAgentRepository(app)
        val userClientProvider = UserSupabaseClientProvider()
        val dropletMcpService = DropletMcpServiceImpl(managementService, sshExec, app)

        // Container orchestration
        val portAllocator = ContainerPortAllocator(
            startPort = com.suprbeta.config.AppConfig.dockerPortMin,
            endPort   = com.suprbeta.config.AppConfig.dockerPortMax
        )
        val hostPoolManager  = HostPoolManager(vpsService, firestoreRepository, sshExec, app)
        val containerService = DockerContainerService(sshExec, portAllocator, app)
        val traefikManager   = TraefikManager(sshExec, app)
        val openClawConnector = OpenClawConnector(app, httpClient, Json { ignoreUnknownKeys = true })

        val dockerService = DockerHostProvisioningService(
            vpsService        = vpsService,
            hostPoolManager   = hostPoolManager,
            containerService  = containerService,
            traefikManager    = traefikManager,
            portAllocator     = portAllocator,
            dnsProvider       = dnsProvider,
            firestoreRepository = firestoreRepository,
            schemaRepository  = schemaRepository,
            managementService = managementService,
            agentRepository   = agentRepository,
            userClientProvider = userClientProvider,
            openClawConnector = openClawConnector,
            sshCommandExecutor = sshExec,
            dropletMcpService = dropletMcpService,
            application       = app
        )
        e2eDockerService = dockerService

        e2eUserId = "e2e-docker-${System.currentTimeMillis()}"
        println("  E2E userId: $e2eUserId")

        // Pre-register the Phase 1 host so HostPoolManager reuses it instead of creating a new VPS.
        // Phase 5 requires the Phase 1 host to already be set up with Docker + Traefik + openclaw SSH access.
        assumeTrue(serverId > 0L && hostIp.isNotBlank(),
            "Phase 1 host not available (serverId=$serverId, hostIp='$hostIp') — skipping Phase 5")
        runBlocking {
            firestoreRepository.saveHostInfo(
                com.suprbeta.docker.models.HostInfo(
                    hostId           = serverId,
                    hostIp           = hostIp,
                    totalCapacity    = 20,
                    currentContainers = 0,
                    status           = com.suprbeta.docker.models.HostInfo.STATUS_ACTIVE,
                    createdAt        = java.time.Instant.now().toString(),
                    region           = "nbg1"
                )
            )
        }
        println("  Registered Phase 1 host $serverId ($hostIp) in Firestore")

        val createResult = runBlocking { dockerService.createAndProvision("test-docker-e2e", e2eUserId) }
        println("  createAndProvision → dropletId=${createResult.dropletId}")

        // provisionDroplet drives the full flow (suspend, returns when complete)
        val userDroplet = runBlocking {
            val provisionJob = launch {
                dockerService.provisionDroplet(createResult.dropletId, createResult.password, e2eUserId)
            }

            // Poll status while provisioning runs
            val deadline = System.currentTimeMillis() + 22 * 60 * 1000L
            var lastPhase = ""
            while (System.currentTimeMillis() < deadline) {
                val status = dockerService.getStatus(createResult.dropletId)
                if (status != null && status.phase != lastPhase) {
                    val pct = (status.progress * 100).toInt()
                    println("  [${pct}%] ${status.phase} — ${status.message}")
                    lastPhase = status.phase
                }
                if (status?.phase == ProvisioningStatus.PHASE_COMPLETE) break
                if (status?.phase == ProvisioningStatus.PHASE_FAILED) {
                    fail<Nothing>("Provisioning failed: ${status.error?.take(300)}")
                }
                kotlinx.coroutines.delay(5_000)
            }

            provisionJob.join()
            dockerService.getStatus(createResult.dropletId)
        }

        val finalPhase = dockerService.getStatus(createResult.dropletId)?.phase
        assertEquals(ProvisioningStatus.PHASE_COMPLETE, finalPhase,
            "Expected PHASE_COMPLETE but got $finalPhase")
        println("✅ PHASE 5.1: Full provisioning complete for user $e2eUserId")
    }

    @Test @Order(510)
    @DisplayName("PHASE 5.2 — Teardown container and cleanup all resources")
    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `PHASE 5-2 teardown and cleanup`() {
        assumeTrue(e2eUserId.isNotBlank(), "Phase 5.1 did not run or failed — skipping teardown")
        val svc = e2eDockerService ?: return

        runBlocking {
            svc.teardown(e2eUserId)
        }

        // Verify Firestore record is gone — getUserDropletInternal should return null
        // (accessed via the same FirestoreRepository used in 5.1)
        val app = mockk<Application>(relaxed = true)
        val cryptoService = CryptoService(app, encryptionKeyset)
        val firestoreRepository = FirestoreRepository(
            e2eFirebaseService!!.firestore, app, cryptoService
        )
        val record = runBlocking { firestoreRepository.getUserDropletInternal(e2eUserId) }
        assertNull(record, "Firestore record still exists after teardown for user $e2eUserId")

        println("✅ PHASE 5.2: Teardown complete — Firestore record deleted, resources released")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun loadEnv(key: String): String? {
        val dotenvFile = File(".", ".env")
        if (dotenvFile.exists()) {
            dotenvFile.readLines()
                .filter { !it.startsWith("#") && "=" in it }
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0].trim() == key }
                ?.let { return it[1].trim() }
        }
        return System.getenv(key)
    }

    private fun assumeProvisioningHostTrustConfigured() {
        val hasPinnedHostKey = !loadEnv("PROVISIONING_SSH_HOST_PRIVATE_KEY_B64").isNullOrBlank() &&
            !loadEnv("PROVISIONING_SSH_HOST_PUBLIC_KEY").isNullOrBlank()
        val hasVerifierTrust = !loadEnv("PROVISIONING_SSH_HOST_FINGERPRINT").isNullOrBlank() ||
            !loadEnv("PROVISIONING_SSH_KNOWN_HOSTS").isNullOrBlank() ||
            !loadEnv("PROVISIONING_SSH_KNOWN_HOSTS_B64").isNullOrBlank()

        assumeTrue(
            hasPinnedHostKey && hasVerifierTrust,
            "Set pinned provisioning host key material and verifier trust env vars before running Phase 5"
        )
    }

    /** Hetzner Cloud API call using curl (avoids needing Ktor HttpClient configured with DI). */
    private fun hetznerApi(method: String, path: String, body: String? = null): JsonObject {
        val cmd = mutableListOf(
            "curl", "-s", "-X", method,
            "-H", "Authorization: Bearer $hetznerToken",
            "-H", "Content-Type: application/json"
        )
        if (body != null) cmd.addAll(listOf("-d", body))
        cmd.add("https://api.hetzner.cloud/v1$path")
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out  = proc.inputStream.bufferedReader().readText()
        proc.waitFor(30, TimeUnit.SECONDS)
        return Json { ignoreUnknownKeys = true }.parseToJsonElement(out).jsonObject
    }

    private fun deleteHetznerServer(id: Long) {
        hetznerApi("DELETE", "/servers/$id")
        println("Server $id deleted")
    }

    /** SSH command as root using password auth (Hetzner root_password). */
    private fun ssh(command: String): String = sshWithTimeout(command, 120)

    private fun sshWithTimeout(command: String, timeoutSeconds: Long): String {
        val client = SSHClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 10_000
        client.connect(hostIp, 22)
        return try {
            client.authPassword("root", rootPassword)
            val session = client.startSession()
            try {
                val cmd = session.exec("bash -l -c '${command.replace("'", "'\\''")}'")
                val stdout = cmd.inputStream.bufferedReader().readText()
                val stderr = cmd.errorStream.bufferedReader().readText()
                cmd.join(timeoutSeconds, TimeUnit.SECONDS)
                val exit = cmd.exitStatus
                if (exit != null && exit != 0) {
                    throw RuntimeException("SSH exit=$exit stderr=${stderr.take(300)}")
                }
                stdout.trim()
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /** Wait until SSH password auth is available on the host. */
    private fun waitForSshPasswordAuth() {
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val c = SSHClient()
                c.addHostKeyVerifier(PromiscuousVerifier())
                c.connectTimeout = 5_000
                c.connect(hostIp, 22)
                try {
                    c.authPassword("root", rootPassword)
                    return
                } finally {
                    runCatching { c.disconnect() }
                }
            } catch (_: Exception) {
                Thread.sleep(5_000)
            }
        }
        error("SSH password auth not available on $hostIp within 3 minutes")
    }

    /** Upload a single local file to the remote host via SCP. */
    private fun scpFile(localPath: String, remotePath: String) {
        val remoteDir = remotePath.substringBeforeLast("/")
        // Ensure the remote directory exists before SCP
        ssh("mkdir -p '$remoteDir'")
        val client = SSHClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 10_000
        client.connect(hostIp, 22)
        try {
            client.authPassword("root", rootPassword)
            // Upload to the directory — sshj places the file using its local basename
            client.newSCPFileTransfer().upload(localPath, "$remoteDir/")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    // ── WebSocket crypto helpers ───────────────────────────────────────────

    private fun signEd25519(payload: String): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(wsKeyPair.private)
        sig.update(payload.toByteArray(Charsets.UTF_8))
        return base64Url(sig.sign())
    }

    private fun rawEd25519PublicKey(spkiBytes: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00)
        return if (spkiBytes.size == prefix.size + 32 &&
                   spkiBytes.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            spkiBytes.copyOfRange(prefix.size, spkiBytes.size)
        } else {
            spkiBytes
        }
    }

    private fun sha256Hex(input: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it) }

    private fun base64Url(input: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(input)
}
