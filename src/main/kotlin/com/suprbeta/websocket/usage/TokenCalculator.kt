package com.suprbeta.websocket.usage

import com.suprbeta.websocket.models.WebSocketFrame
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Calculates token count from websocket frame payloads.
 */
class TokenCalculator(
    application: Application,
    private val httpClient: HttpClient,
    private val json: Json,
    private val geminiApiKey: String? = System.getenv("GEMINI_API_KEY")
) {
    companion object {
        private const val GEMINI_COUNT_TOKENS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash"
    }

    private val logger = application.log
    private val missingKeyWarned = AtomicBoolean(false)
    private val excludedKeys = setOf(
        "auth", "token", "signature", "nonce", "id", "requestId",
        "sessionId", "sessionKey", "method", "event", "type"
    )

    suspend fun countFrameTokens(frame: WebSocketFrame, model: String): Long {
        val tokenizableSegments = mutableListOf<String>()
        collectTokenizableStrings(frame.params, tokenizableSegments)
        collectTokenizableStrings(frame.payload, tokenizableSegments)
        collectTokenizableStrings(frame.result, tokenizableSegments)

        val merged = tokenizableSegments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        if (merged.isEmpty()) return 0

        return try {
            countWithGemini(model, merged)
        } catch (e: Exception) {
            logger.warn("Gemini countTokens failed for model=$model, falling back to heuristic: ${e.message}")
            heuristicTokens(merged)
        }
    }

    private suspend fun countWithGemini(model: String, text: String): Long {
        val key = geminiApiKey?.trim()
        if (key.isNullOrEmpty()) {
            if (missingKeyWarned.compareAndSet(false, true)) {
                logger.warn("GEMINI_API_KEY not configured; token counting will use heuristic fallback")
            }
            return heuristicTokens(text)
        }

        val encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8)
        val response = httpClient.post("$GEMINI_COUNT_TOKENS_BASE_URL/$encodedModel:countTokens?key=$key") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            put("text", text)
                                        }
                                    )
                                }
                            }
                        )
                    }
                }.toString()
            )
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }

        val body = response.bodyAsText()
        val totalTokens = json.parseToJsonElement(body)
            .jsonObject["totalTokens"]
            ?.jsonPrimitive
            ?.longOrNull

        return totalTokens ?: throw IllegalStateException("Missing totalTokens in countTokens response")
    }

    private fun heuristicTokens(text: String): Long {
        return ceil(text.length / 4.0).toLong()
    }

    private fun collectTokenizableStrings(element: JsonElement?, output: MutableList<String>) {
        when (element) {
            null -> return
            is JsonObject -> {
                element.forEach { (key, value) ->
                    if (excludedKeys.contains(key)) return@forEach
                    collectTokenizableStrings(value, output)
                }
            }
            is JsonArray -> {
                element.forEach { item ->
                    collectTokenizableStrings(item, output)
                }
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    output.add(element.content)
                }
            }
        }
    }
}
