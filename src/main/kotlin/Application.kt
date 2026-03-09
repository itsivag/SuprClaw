package com.suprbeta

import com.suprbeta.admin.configureAdminRoutes
import com.suprbeta.config.AppConfig
import com.suprbeta.digitalocean.DigitalOceanService
import com.suprbeta.digitalocean.DnsService
import com.suprbeta.digitalocean.DropletConfigurationService
import com.suprbeta.digitalocean.DropletConfigurationServiceImpl
import com.suprbeta.digitalocean.DropletMcpServiceImpl
import com.suprbeta.digitalocean.DropletProvisioningService
import com.suprbeta.digitalocean.AgentWorkspaceServiceImpl
import com.suprbeta.digitalocean.configureAgentRoutes
import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.digitalocean.configureDropletRoutes
import com.suprbeta.docker.ContainerPortAllocator
import com.suprbeta.docker.DockerContainerService
import com.suprbeta.docker.DockerHostProvisioningService
import com.suprbeta.docker.HostPoolManager
import com.suprbeta.docker.TraefikManager
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseService
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.hetzner.HetznerDnsService
import com.suprbeta.hetzner.HetznerService
import com.suprbeta.provider.DnsProvider
import com.suprbeta.provider.VpsService
import com.suprbeta.supabase.SupabaseAgentRepository
import com.suprbeta.supabase.SelfHostedSupabaseManagementService
import com.suprbeta.supabase.SupabaseManagementService
import com.suprbeta.supabase.SupabaseSchemaRepository
import com.suprbeta.supabase.SupabaseTaskRepository
import com.suprbeta.supabase.UserSupabaseClientProvider
import com.suprbeta.supabase.configureTaskRoutes
import com.suprbeta.marketplace.MarketplaceService
import com.suprbeta.marketplace.configureMarketplaceRoutes
import com.suprbeta.firebase.configureFcmRoutes
import com.suprbeta.supabase.configureWebhookRoutes
import com.suprbeta.usage.configureUsageRoutes
import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.ProxySessionManager
import com.suprbeta.websocket.configureWebSocketRoutes
import com.suprbeta.websocket.pipeline.AuthInterceptor
import com.suprbeta.websocket.pipeline.LoggingInterceptor
import com.suprbeta.websocket.pipeline.MessagePipeline
import com.suprbeta.websocket.pipeline.UsageInterceptor
import com.suprbeta.websocket.usage.TokenCalculator
import configureRouting
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main(args: Array<String>) {
    configureJvmNetworking()
    EngineMain.main(args)
}

fun Application.module() {
    // Initialize app configuration
    AppConfig.initialize(this)

    configureSerialization()
    configureHTTP()
    val cryptoService = com.suprbeta.core.CryptoService(this)
    val (firebaseAuthService, firestoreRepository, remoteConfigService) = configureFirebase(cryptoService)
    val agentRepository = SupabaseAgentRepository(this)
    val taskRepository = SupabaseTaskRepository(this)
    val schemaRepository = SupabaseSchemaRepository(this)
    val userClientProvider = UserSupabaseClientProvider()

    // Create shared HttpClient for API calls
    val httpClient = createHttpClient()

    val managementService: SupabaseManagementService = SelfHostedSupabaseManagementService(httpClient, this)
    log.info("Supabase mode: self-hosted")
    installSupabaseStartupRepair(managementService)
    val marketplaceService = MarketplaceService(httpClient)

    configureWebSockets(httpClient, firebaseAuthService, firestoreRepository, remoteConfigService)
    val digitalOceanServices = configureDigitalOcean(
        httpClient,
        firestoreRepository,
        agentRepository,
        schemaRepository,
        managementService,
        userClientProvider,
        marketplaceService
    )
    configureTaskRoutes(taskRepository, firestoreRepository, userClientProvider)
    configureWebhookRoutes(firestoreRepository, userClientProvider, agentRepository, httpClient, managementService.webhookSecret)
    configureFcmRoutes(firestoreRepository)
    configureMarketplaceRoutes(digitalOceanServices.configuringService, marketplaceService)
    configureUsageRoutes(firestoreRepository, remoteConfigService)
    configureAdminRoutes(firestoreRepository, digitalOceanServices.provisioningService)
    configureRouting()
}

internal fun Application.installSupabaseStartupRepair(managementService: SupabaseManagementService) {
    monitor.subscribe(ApplicationStarted) {
        launch {
            try {
                managementService.reconcileConfiguration()
            } catch (e: Exception) {
                log.error("Self-hosted Supabase startup reconciliation failed", e)
            }
        }
    }
}

fun Application.configureSerialization() {
    install(ServerContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }
}

fun createHttpClient(): HttpClient {
    return HttpClient(CIO) {
        engine {
            // Keep engine-level retries deterministic; OpenClawConnector already retries 3 times.
            endpoint {
                connectTimeout = 20_000
                connectAttempts = 1
            }
            requestTimeout = 30_000
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(WebSockets) {
            // Keep upstream OpenClaw WebSocket sessions alive across idle periods.
            pingIntervalMillis = 15_000
        }
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }
}

private fun configureJvmNetworking() {
    val dotenv = io.github.cdimascio.dotenv.dotenv { ignoreIfMissing = true; directory = "." }
    val preferIpv4 = (dotenv["PREFER_IPV4_STACK"] ?: System.getenv("PREFER_IPV4_STACK") ?: "false").equals("true", ignoreCase = true)
    if (preferIpv4) {
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }
}

fun Application.configureWebSockets(
    httpClient: HttpClient,
    authService: FirebaseAuthService,
    firestoreRepository: FirestoreRepository,
    remoteConfigService: com.suprbeta.firebase.RemoteConfigService
) {
    // Configure shared JSON serializer
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // Install server-side WebSockets plugin
    install(io.ktor.server.websocket.WebSockets) {
        // Keep mobile client connections healthy through intermediaries/load balancers.
        pingPeriodMillis = 15_000
        timeoutMillis = 60_000
    }

    val tokenCalculator = TokenCalculator(
        application = this,
        json = json
    )
    val usageInterceptor = UsageInterceptor(
        firestoreRepository = firestoreRepository,
        tokenCalculator = tokenCalculator,
        application = this
    )
    
    val rateLimitInterceptor = com.suprbeta.websocket.pipeline.RateLimitInterceptor(
        application = this,
        remoteConfigService = remoteConfigService
    )

    // Initialize pipeline with interceptors
    val messagePipeline = MessagePipeline(
        application = this,
        interceptors = listOf(
            LoggingInterceptor(this),
            AuthInterceptor(authService, this),
            rateLimitInterceptor,
            usageInterceptor
        )
    )

    // Initialize OpenClaw connector
    val openClawConnector = OpenClawConnector(
        application = this,
        httpClient = httpClient,
        json = json
    )
    val sshCommandExecutor = SshCommandExecutorImpl(this)

    // Initialize session manager with Firestore for VPS routing
    val sessionManager = ProxySessionManager(
        application = this,
        openClawConnector = openClawConnector,
        messagePipeline = messagePipeline,
        usageInterceptor = usageInterceptor,
        json = json,
        firestoreRepository = firestoreRepository,
        sshCommandExecutor = sshCommandExecutor
    )

    // Configure WebSocket routes
    configureWebSocketRoutes(sessionManager, authService)

    // Graceful shutdown - close all sessions when application stops
    monitor.subscribe(ApplicationStopped) {
        log.info("Application stopping - closing all WebSocket sessions")
        runBlocking {
            sessionManager.closeAllSessions()
            httpClient.close()
        }
        log.info("All sessions closed")
    }

    log.info("WebSocket proxy initialized and ready")
}

fun Application.configureDigitalOcean(
    httpClient: HttpClient,
    firestoreRepository: FirestoreRepository,
    agentRepository: SupabaseAgentRepository,
    schemaRepository: SupabaseSchemaRepository,
    managementService: SupabaseManagementService,
    userClientProvider: UserSupabaseClientProvider,
    marketplaceService: MarketplaceService
): DigitalOceanServices {
    val dotenv = io.github.cdimascio.dotenv.dotenv { ignoreIfMissing = true; directory = "." }
    val sshCommandExecutor = SshCommandExecutorImpl(this)
    val dropletMcpService = DropletMcpServiceImpl(
        managementService = managementService,
        sshCommandExecutor = sshCommandExecutor,
        application = this
    )

    val vpsProviderName = (dotenv["VPS_PROVIDER"] ?: System.getenv("VPS_PROVIDER"))?.lowercase() ?: "hetzner"
    val (vpsService, dnsProvider) = createProviders(httpClient, vpsProviderName)
    log.info("Provisioning mode: docker (vps provider: $vpsProviderName)")

    val portAllocator = ContainerPortAllocator(
        startPort = AppConfig.dockerPortMin,
        endPort = AppConfig.dockerPortMax
    )
    val hostPoolManager = HostPoolManager(vpsService, firestoreRepository, sshCommandExecutor, this)
    val containerService = DockerContainerService(sshCommandExecutor, portAllocator, this)
    val traefikManager = TraefikManager(sshCommandExecutor, this)

    val provisioningService: DropletProvisioningService = DockerHostProvisioningService(
        vpsService = vpsService,
        hostPoolManager = hostPoolManager,
        containerService = containerService,
        traefikManager = traefikManager,
        portAllocator = portAllocator,
        dnsProvider = dnsProvider,
        firestoreRepository = firestoreRepository,
        schemaRepository = schemaRepository,
        managementService = managementService,
        agentRepository = agentRepository,
        userClientProvider = userClientProvider,
        openClawConnector = OpenClawConnector(this, httpClient, Json { ignoreUnknownKeys = true }),
        sshCommandExecutor = sshCommandExecutor,
        dropletMcpService = dropletMcpService,
        application = this
    )

    // Recover port allocations on startup to avoid collisions after restart
    monitor.subscribe(ApplicationStarted) {
        launch {
            try {
                val hosts = hostPoolManager.listActiveHosts()
                for (host in hosts) {
                    try {
                        val portsOutput = sshCommandExecutor.runSshCommand(
                            host.hostIp,
                            "docker ps --filter 'name=openclaw-' --format '{{.Ports}}' 2>/dev/null || echo ''"
                        )
                        val usedPorts = portsOutput.lines()
                            .filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                Regex("""127\.0\.0\.1:(\d+)->""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            }
                            .toSet()
                        portAllocator.preallocatePorts(host.hostId, usedPorts)
                        log.info("Port recovery: host ${host.hostId} → ${usedPorts.size} ports pre-allocated")
                    } catch (e: Exception) {
                        log.warn("Port recovery skipped for host ${host.hostId}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log.warn("Port recovery failed: ${e.message}")
            }
        }
    }

    val configuringService: DropletConfigurationService = DropletConfigurationServiceImpl(
        firestoreRepository = firestoreRepository,
        agentRepository = agentRepository,
        userClientProvider = userClientProvider,
        sshCommandExecutor = sshCommandExecutor,
        dropletMcpService = dropletMcpService,
        application = this
    )
    val workspaceService = AgentWorkspaceServiceImpl(
        firestoreRepository = firestoreRepository,
        agentRepository = agentRepository,
        userClientProvider = userClientProvider,
        marketplaceService = marketplaceService,
        sshCommandExecutor = sshCommandExecutor,
        application = this
    )
    configureDropletRoutes(provisioningService, firestoreRepository)
    configureAgentRoutes(
        configuringService,
        workspaceService,
        firestoreRepository,
        agentRepository,
        userClientProvider
    )
    return DigitalOceanServices(
        configuringService = configuringService,
        provisioningService = provisioningService
    )
}

data class DigitalOceanServices(
    val configuringService: DropletConfigurationService,
    val provisioningService: DropletProvisioningService
)

private fun Application.createProviders(
    httpClient: HttpClient,
    providerName: String
): Pair<VpsService, DnsProvider> {
    return when (providerName) {
        "hetzner" -> {
            log.info("Initializing Hetzner Cloud VPS and DNS providers")
            Pair(HetznerService(httpClient, this), HetznerDnsService(httpClient, this))
        }
        else -> {
            if (providerName != "digitalocean") {
                log.warn("Unknown VPS_PROVIDER '$providerName', falling back to digitalocean")
            }
            log.info("Initializing DigitalOcean VPS and DNS providers")
            val doService = DigitalOceanService(httpClient, this)
            Pair(doService, DnsService(httpClient, this))
        }
    }
}

fun Application.configureFirebase(cryptoService: com.suprbeta.core.CryptoService): Triple<FirebaseAuthService, FirestoreRepository, com.suprbeta.firebase.RemoteConfigService> {
    // Initialize Firebase services
    val firebaseService = FirebaseService(this)
    val firestoreRepository = FirestoreRepository(firebaseService.firestore, this, cryptoService)
    val firebaseAuthService = FirebaseAuthService(firebaseService, this)
    val remoteConfigService = com.suprbeta.firebase.RemoteConfigService(this, firebaseService.firebaseApp)

    // Install HTTP authentication plugin
    install(FirebaseAuthPlugin) {
        authService = firebaseAuthService
    }

    // Graceful shutdown - close Firebase when application stops
    monitor.subscribe(ApplicationStopped) {
        log.info("Application stopping - shutting down Firebase")
        runBlocking {
            firebaseService.shutdown()
        }
        log.info("Firebase shutdown complete")
    }

    log.info("Firebase Authentication and Firestore initialized and ready")

    return Triple(firebaseAuthService, firestoreRepository, remoteConfigService)
}
