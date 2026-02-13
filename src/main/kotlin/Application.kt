package com.suprbeta

import com.suprbeta.websocket.OpenClawConnector
import com.suprbeta.websocket.ProxySessionManager
import com.suprbeta.websocket.configureWebSocketRoutes
import com.suprbeta.websocket.pipeline.AuthInterceptor
import com.suprbeta.websocket.pipeline.LoggingInterceptor
import com.suprbeta.websocket.pipeline.MessagePipeline
import configureRouting
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureHTTP()
    configureWebSockets()
    configureRouting()
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }
}

fun Application.configureWebSockets() {
    // Configure shared JSON serializer
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // Install server-side WebSockets plugin
    install(io.ktor.server.websocket.WebSockets)

    // Create HttpClient with WebSocket support for OpenClaw connections
    val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.websocket.WebSockets)
    }

    // Initialize pipeline with interceptors
    val messagePipeline = MessagePipeline(
        application = this,
        interceptors = listOf(
            LoggingInterceptor(this),
            AuthInterceptor()
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
    configureWebSocketRoutes(sessionManager)

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
