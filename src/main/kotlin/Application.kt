package com.suprbeta

import com.suprbeta.config.AppConfig
import com.suprbeta.digitalocean.DigitalOceanService
import com.suprbeta.digitalocean.DnsService
import com.suprbeta.digitalocean.DropletProvisioningService
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
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main(args: Array<String>) {
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

    configureWebSockets(httpClient, firebaseAuthService)
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
        install(WebSockets)
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }
}

fun Application.configureWebSockets(httpClient: HttpClient, authService: FirebaseAuthService) {
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

    // Initialize session manager
    val sessionManager = ProxySessionManager(
        application = this,
        openClawConnector = openClawConnector,
        messagePipeline = messagePipeline,
        json = json
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
    val provisioningService = DropletProvisioningService(digitalOceanService, dnsService, firestoreRepository, this)
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
