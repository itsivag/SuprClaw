package com.suprbeta.connector

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class NangoProviderDefinition(
    val provider: String,
    val displayName: String,
    val providerConfigKey: String
)

data class NangoConnectSessionResult(
    val token: String,
    val connectLink: String?,
    val expiresAt: String
)

data class NangoActionTool(
    val name: String,
    val description: String?,
    val parameters: JsonObject
)

data class NangoActionTriggerResult(
    val async: Boolean,
    val output: JsonElement? = null,
    val actionId: String? = null,
    val statusUrl: String? = null
)

data class NangoConnectionRecord(
    val connectionId: String,
    val provider: String,
    val providerConfigKey: String,
    val createdAt: String?,
    val updatedAt: String?,
    val tags: Map<String, String>,
    val metadata: Map<String, String>,
    val errors: List<String>,
    val raw: JsonObject
)

data class NangoProxyRequest(
    val method: HttpMethod,
    val endpoint: String,
    val providerConfigKey: String,
    val connectionId: String,
    val baseUrlOverride: String? = null,
    val retries: Int? = null,
    val queryParameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null
)

data class NangoProxyResponse(
    val statusCode: Int,
    val body: String,
    val headers: Headers
)

class NangoService(
    private val httpClient: HttpClient,
    application: Application,
    private val envOverride: (String) -> String? = { null }
) {
    private val logger = application.log
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
    private val json = Json { ignoreUnknownKeys = true }

    private val baseUrl: String by lazy {
        (env("NANGO_BASE_URL").ifBlank { "https://api.nango.dev" }).trimEnd('/')
    }

    private val secretKey: String by lazy { env("NANGO_SECRET_KEY") }
    private val webhookSecret: String by lazy { env("NANGO_WEBHOOK_SECRET") }

    val callbackForwardUrl: String by lazy {
        env("NANGO_OAUTH_CALLBACK_FORWARD_URL")
            .ifBlank { "https://api.nango.dev/oauth/callback" }
    }

    val providers: Map<String, NangoProviderDefinition> by lazy {
        listOf(
            NangoProviderDefinition(
                provider = "google",
                displayName = "Google",
                providerConfigKey = env("NANGO_GOOGLE_PROVIDER_CONFIG_KEY").ifBlank { "google" }
            ),
            NangoProviderDefinition(
                provider = "github",
                displayName = "GitHub",
                providerConfigKey = env("NANGO_GITHUB_PROVIDER_CONFIG_KEY").ifBlank { "github" }
            ),
            NangoProviderDefinition(
                provider = "facebook",
                displayName = "Facebook",
                providerConfigKey = env("NANGO_FACEBOOK_PROVIDER_CONFIG_KEY").ifBlank { "facebook" }
            )
        ).associateBy { it.provider }
    }

    fun isConfigured(): Boolean = secretKey.isNotBlank()

    fun requireProvider(provider: String): NangoProviderDefinition {
        val normalized = provider.trim().lowercase()
        if (!isConfigured()) {
            throw IllegalStateException("NANGO_SECRET_KEY is not configured")
        }
        return providers[normalized]
            ?: throw IllegalArgumentException("Unsupported connector provider '$normalized'")
    }

    suspend fun createConnectSession(
        provider: NangoProviderDefinition,
        tags: Map<String, String>
    ): NangoConnectSessionResult {
        val payload = buildJsonObject {
            put("allowed_integrations", buildJsonArray { add(JsonPrimitive(provider.providerConfigKey)) })
            put("tags", tags.toJsonObject())
        }
        val response = postJson("/connect/sessions", payload)
        return parseConnectSession(response)
    }

    suspend fun createReconnectSession(
        provider: NangoProviderDefinition,
        connectionId: String,
        tags: Map<String, String>
    ): NangoConnectSessionResult {
        val payload = buildJsonObject {
            put("connection_id", JsonPrimitive(connectionId))
            put("integration_id", JsonPrimitive(provider.providerConfigKey))
            put("tags", tags.toJsonObject())
        }
        val response = postJson("/connect/sessions/reconnect", payload)
        return parseConnectSession(response)
    }

    suspend fun getConnection(
        providerConfigKey: String,
        connectionId: String
    ): NangoConnectionRecord {
        val response = getJson("/connections/$connectionId") {
            parameter("provider_config_key", providerConfigKey)
        }
        val root = response.jsonObject
        return NangoConnectionRecord(
            connectionId = root.string("connection_id") ?: connectionId,
            provider = root.string("provider") ?: "",
            providerConfigKey = root.string("provider_config_key") ?: providerConfigKey,
            createdAt = root.string("created_at") ?: root.string("created"),
            updatedAt = root.string("updated_at") ?: root.string("updated"),
            tags = root.objectValue("tags").stringMap(),
            metadata = root.objectValue("metadata").stringMap(),
            errors = root.arrayValue("errors").mapNotNull { element ->
                element.jsonObject.string("type") ?: element.jsonObject.string("description")
            },
            raw = root
        )
    }

    suspend fun deleteConnection(
        providerConfigKey: String,
        connectionId: String
    ) {
        val response = httpClient.delete("$baseUrl/connections/$connectionId") {
            authorize()
            parameter("provider_config_key", providerConfigKey)
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Failed to delete Nango connection: ${response.status.value} ${response.bodyAsText()}")
        }
    }

    suspend fun listActionTools(
        providerConfigKey: String
    ): List<NangoActionTool> {
        val response = getJson("/scripts/config") {
            parameter("format", "nango")
        }
        val configs = response.jsonArray
        val matchingConfig = configs
            .map { it.jsonObject }
            .firstOrNull { it.string("providerConfigKey") == providerConfigKey }
            ?: return emptyList()

        return matchingConfig.arrayValue("actions").mapNotNull { action ->
            val actionObject = runCatching { action.jsonObject }.getOrNull() ?: return@mapNotNull null
            val rawSchema = actionObject.objectValue("json_schema")
            val schemaDefinitions = rawSchema.objectValue("definitions").let { definitions ->
                if (definitions.isNotEmpty()) definitions else rawSchema.objectValue("\$defs")
            }
            val parameters = schemaDefinitions.takeIf { it.isNotEmpty() && actionObject.string("input") != null }
                ?.get(actionObject.string("input")!!)
                ?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: rawSchema
            NangoActionTool(
                name = actionObject.string("name") ?: return@mapNotNull null,
                description = actionObject.string("description"),
                parameters = buildJsonObject {
                    put("type", JsonPrimitive(parameters.string("type") ?: "object"))
                    put("properties", parameters["properties"] ?: JsonObject(emptyMap()))
                    put("required", parameters["required"] ?: JsonArray(emptyList()))
                }
            )
        }
    }

    suspend fun triggerAction(
        providerConfigKey: String,
        connectionId: String,
        actionName: String,
        input: JsonElement? = null,
        async: Boolean = false,
        maxRetries: Int? = null
    ): NangoActionTriggerResult {
        val payload = buildJsonObject {
            put("action_name", JsonPrimitive(actionName))
            input?.let { put("input", it) }
        }
        val response = httpClient.post("$baseUrl/action/trigger") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("Provider-Config-Key", providerConfigKey)
            header("Connection-Id", connectionId)
            if (async) {
                header("X-Async", "true")
            }
            maxRetries?.let { header("X-Max-Retries", it.toString()) }
            setBody(payload)
        }
        val decoded = decodeJson(response, "/action/trigger")
        val root = runCatching { decoded.jsonObject }.getOrNull()
        val statusUrl = root?.string("statusUrl")
        val actionId = root?.string("id")
        return if (statusUrl != null && actionId != null) {
            NangoActionTriggerResult(
                async = true,
                output = null,
                actionId = actionId,
                statusUrl = statusUrl
            )
        } else {
            NangoActionTriggerResult(
                async = false,
                output = decoded
            )
        }
    }

    suspend fun proxy(request: NangoProxyRequest): NangoProxyResponse {
        val normalizedEndpoint = request.endpoint.trimStart('/')
        val response = httpClient.request("$baseUrl/proxy/$normalizedEndpoint") {
            method = request.method
            authorize()
            header("Provider-Config-Key", request.providerConfigKey)
            header("Connection-Id", request.connectionId)
            request.baseUrlOverride?.takeIf { it.isNotBlank() }?.let { header("Base-Url-Override", it) }
            request.retries?.let { header("Retries", it.toString()) }
            request.queryParameters.forEach { (key, value) -> parameter(key, value) }
            request.headers.forEach { (key, value) -> header(key, value) }
            request.body?.let { setBody(it) }
        }
        return NangoProxyResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers
        )
    }

    fun verifyWebhookSignature(rawBody: String, headerValue: String?): Boolean {
        if (webhookSecret.isBlank()) {
            throw IllegalStateException("NANGO_WEBHOOK_SECRET is not configured")
        }
        val provided = headerValue?.trim()?.lowercase().orEmpty()
        if (provided.isBlank()) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(rawBody.toByteArray(Charsets.UTF_8))
        val expected = digest.joinToString(separator = "") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
    }

    fun buildCallbackForwardUrl(provider: String, queryParameters: Parameters): String {
        requireProvider(provider)
        return URLBuilder(callbackForwardUrl).apply {
            queryParameters.forEach { key, values ->
                values.forEach { parameters.append(key, it) }
            }
        }.buildString()
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonElement {
        val response = httpClient.post("$baseUrl$path") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        return decodeJson(response, path)
    }

    private suspend fun getJson(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): JsonElement {
        val response = httpClient.get("$baseUrl$path") {
            authorize()
            block()
        }
        return decodeJson(response, path)
    }

    private fun parseConnectSession(payload: JsonElement): NangoConnectSessionResult {
        val data = payload.jsonObject.objectValue("data")
        return NangoConnectSessionResult(
            token = data.string("token")
                ?: throw IllegalStateException("Nango connect session response did not include token"),
            connectLink = data.string("connect_link"),
            expiresAt = data.string("expires_at")
                ?: throw IllegalStateException("Nango connect session response did not include expires_at")
        )
    }

    private suspend fun decodeJson(response: HttpResponse, path: String): JsonElement {
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) {
            logger.warn("Nango request failed path=$path status=${response.status.value} body=$raw")
            throw IllegalStateException("Nango request failed (${response.status.value})")
        }
        return runCatching { json.parseToJsonElement(raw) }
            .getOrElse { throw IllegalStateException("Failed to parse Nango response from $path", it) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        if (secretKey.isBlank()) {
            throw IllegalStateException("NANGO_SECRET_KEY is not configured")
        }
        header("Authorization", "Bearer $secretKey")
    }

    private fun env(key: String): String = envOverride(key) ?: dotenv[key] ?: System.getenv(key) ?: ""
}

private fun Map<String, String>.toJsonObject(): JsonObject = buildJsonObject {
    forEach { (key, value) ->
        put(key, JsonPrimitive(value))
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.takeIf { it !is JsonNull }?.jsonObject ?: emptyMap<String, JsonElement>().let(::JsonObject)

private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.takeIf { it !is JsonNull }?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.stringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    value.jsonPrimitive.contentOrNull?.let { key to it }
}.toMap()
