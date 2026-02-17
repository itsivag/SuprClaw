package com.suprbeta

import com.suprbeta.config.AppConfig
import com.suprbeta.digitalocean.DigitalOceanService
import com.suprbeta.digitalocean.DnsService
import com.suprbeta.digitalocean.DropletProvisioningService
import com.suprbeta.digitalocean.DropletProvisioningServiceImpl
import com.suprbeta.core.SshCommandExecutorImpl
import com.suprbeta.digitalocean.configureDropletRoutes
import com.suprbeta.firebase.FirebaseAuthPlugin
import com.suprbeta.firebase.FirebaseAuthService
import com.suprbeta.firebase.FirebaseService
import com.suprbeta.firebase.FirestoreRepository
import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.ProxySessionManager
import com.suprbeta.websocket.configureWebSocketRoutes
import com.suprbeta.websocket.pipeline.AuthInterceptor
import com.suprbeta.websocket.pipeline.LoggingInterceptor
import com.suprbeta.websocket.pipeline.MessagePipeline
import configureRouting
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
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
    val (firebaseAuthService, firestoreRepository) = configureFirebase()

    // Create shared HttpClient for API calls
    val httpClient = createHttpClient()

    configureWebSockets(httpClient, firebaseAuthService, firestoreRepository)
    configureDigitalOcean(httpClient, firestoreRepository)
    configureRouting()
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
        install(WebSockets)
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }
}

private fun configureJvmNetworking() {
    val preferIpv4 = (System.getenv("PREFER_IPV4_STACK") ?: "false").equals("true", ignoreCase = true)
    if (preferIpv4) {
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }
}

fun Application.configureWebSockets(httpClient: HttpClient, authService: FirebaseAuthService, firestoreRepository: FirestoreRepository) {
    // Configure shared JSON serializer
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // Install server-side WebSockets plugin
    install(io.ktor.server.websocket.WebSockets)

    // Initialize pipeline with interceptors
    val messagePipeline = MessagePipeline(
        application = this,
        interceptors = listOf(
            LoggingInterceptor(this),
            AuthInterceptor(authService, this)
        )
    )

    // Initialize OpenClaw connector
    val openClawConnector = OpenClawConnector(
        application = this,
        httpClient = httpClient,
        json = json
    )

    // Initialize session manager with Firestore for VPS routing
    val sessionManager = ProxySessionManager(
        application = this,
        openClawConnector = openClawConnector,
        messagePipeline = messagePipeline,
        json = json,
        firestoreRepository = firestoreRepository
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

fun Application.configureDigitalOcean(httpClient: HttpClient, firestoreRepository: FirestoreRepository) {
    val digitalOceanService = DigitalOceanService(httpClient, this)
    val dnsService = DnsService(httpClient, this)
    val sshCommandExecutor = SshCommandExecutorImpl(this)
    val provisioningConnector = OpenClawConnector(
        application = this,
        httpClient = httpClient,
        json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    )
    val provisioningService: DropletProvisioningService = DropletProvisioningServiceImpl(
        digitalOceanService = digitalOceanService,
        dnsService = dnsService,
        firestoreRepository = firestoreRepository,
        openClawConnector = provisioningConnector,
        sshCommandExecutor = sshCommandExecutor,
        application = this
    )
    configureDropletRoutes(digitalOceanService, provisioningService, firestoreRepository)
    log.info("DigitalOcean service initialized with SSH provisioning and DNS management")
}

fun Application.configureFirebase(): Pair<FirebaseAuthService, FirestoreRepository> {
    // Initialize Firebase services
    val firebaseService = FirebaseService(this)
    val firestoreRepository = FirestoreRepository(firebaseService.firestore, this)
    val firebaseAuthService = FirebaseAuthService(firebaseService, this)

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

    return Pair(firebaseAuthService, firestoreRepository)
}
