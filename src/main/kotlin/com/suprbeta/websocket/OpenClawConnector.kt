package com.suprbeta.websocket

import com.suprbeta.websocket.models.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.Properties
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Handles connections to the OpenClaw VPS WebSocket server
 */
class OpenClawConnector(
    private val application: Application,
    private val httpClient: HttpClient,
    private val json: Json
) {
    private val logger = application.log
    private val deviceIdentityPath = resolveDeviceIdentityPath()
    private val keyPair = loadOrCreateDeviceKeyPair(deviceIdentityPath)
    private val devicePublicKeyRaw = extractRawEd25519PublicKey(keyPair.public.encoded)
    private val devicePublicKey = base64Url(devicePublicKeyRaw)
    private val deviceId = sha256Hex(devicePublicKeyRaw)

    init {
        logger.info("OpenClaw device identity initialized: id=$deviceId path=$deviceIdentityPath")
    }

    fun getDeviceId(): String = deviceId

    /**
     * Connect to OpenClaw VPS with retry logic
     *
     * @param token Authentication token
     * @param vpsGatewayUrl The VPS gateway URL to connect to (e.g., https://subdomain.suprclaw.com)
     * @return WebSocketSession if successful, null if all retries failed
     */
    suspend fun connect(token: String, vpsGatewayUrl: String): DefaultWebSocketSession? {
        val maxRetries = 3
        val retryDelay = 1000L // 1 second

        repeat(maxRetries) { attempt ->
            try {
                logger.info("Connecting to OpenClaw VPS at $vpsGatewayUrl (attempt ${attempt + 1}/$maxRetries)...")
                val wsUrl = vpsGatewayUrl
                    .replace("https://", "wss://")

                val session = httpClient.webSocketSession(
                    urlString = "$wsUrl/ws"
                )

                logger.info("Connected to OpenClaw VPS successfully at $vpsGatewayUrl")
                return session

            } catch (e: Exception) {
                logger.error("Failed to connect to OpenClaw VPS at $vpsGatewayUrl (attempt ${attempt + 1}/$maxRetries): ${e.message}")

                if (attempt < maxRetries - 1) {
                    delay(retryDelay)
                }
            }
        }

        logger.error("Failed to connect to OpenClaw VPS at $vpsGatewayUrl after $maxRetries attempts")
        return null
    }

    /**
     * Handle connect.challenge event from OpenClaw VPS
     * This method automatically responds with a full ConnectRequest
     *
     * @param session OpenClaw WebSocket session
     * @param token Authentication token
     * @param challengePayload Raw connect.challenge payload from gateway
     * @param platform Platform identifier (android, ios, etc.)
     */
    suspend fun handleConnectChallenge(
        session: DefaultWebSocketSession,
        token: String,
        challengePayload: JsonElement?,
        platform: String = "proxy"
    ) {
        try {
            val challenge = parseChallenge(challengePayload)
            val signedAt = System.currentTimeMillis()
            val clientId = "cli"
            val clientMode = "cli"
            val role = "operator"
            val scopes = listOf("operator.read", "operator.write")
            val deviceVersion = if (challenge.nonce.isNullOrBlank()) "v1" else "v2"
            val payloadToSign = buildDeviceAuthPayload(
                version = deviceVersion,
                deviceId = deviceId,
                clientId = clientId,
                clientMode = clientMode,
                role = role,
                scopes = scopes,
                signedAt = signedAt,
                nonce = challenge.nonce,
                token = token
            )
            val signature = signPayload(payloadToSign)

            // Build a strict request frame to match OpenClaw protocol expectations exactly.
            val requestJson = buildJsonObject {
                put("type", "req")
                put("id", "1")
                put("method", "connect")
                putJsonObject("params") {
                    put("minProtocol", 3)
                    put("maxProtocol", 3)
                    putJsonObject("client") {
                        put("id", clientId)
                        put("version", "2026.2.15")
                        put("platform", platform)
                        put("mode", clientMode)
                    }
                    put("role", role)
                    put(
                        "scopes",
                        buildJsonArray {
                            scopes.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                    putJsonObject("device") {
                        put("id", deviceId)
                        put("publicKey", devicePublicKey)
                        put("signedAt", signedAt)
                        put("signature", signature)
                        if (!challenge.nonce.isNullOrBlank()) {
                            put("nonce", challenge.nonce)
                        }
                    }
                    put("caps", buildJsonArray { })
                    put("commands", buildJsonArray { })
                    putJsonObject("permissions") { }
                    putJsonObject("auth") {
                        put("token", token)
                    }
                    put("locale", "en-US")
                    put("userAgent", "openclaw-kmp/2026.2.9")
                }
            }.toString()
            session.send(Frame.Text(requestJson))

            logger.info("Sent ConnectRequest to OpenClaw VPS (auto-handled connect.challenge)")

        } catch (e: Exception) {
            logger.error("Failed to handle connect.challenge: ${e.message}", e)
            throw e
        }
    }

    private fun parseChallenge(payload: JsonElement?): ConnectChallenge {
        if (payload == null) return ConnectChallenge()
        val obj = payload.jsonObject
        val nonce = obj["nonce"]?.jsonPrimitive?.contentOrNull
        return ConnectChallenge(
            nonce = nonce
        )
    }

    private fun signPayload(payload: String): String {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(keyPair.private)
        signature.update(payload.toByteArray(StandardCharsets.UTF_8))
        return base64Url(signature.sign())
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun base64Url(input: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input)
    }

    private fun buildDeviceAuthPayload(
        version: String,
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAt: Long,
        nonce: String?,
        token: String
    ): String {
        val payload = mutableListOf(
            version,
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAt.toString(),
            token
        )
        if (version == "v2") {
            payload.add(nonce ?: "")
        }
        return payload.joinToString("|")
    }

    private fun extractRawEd25519PublicKey(spkiBytes: ByteArray): ByteArray {
        // ASN.1 SubjectPublicKeyInfo prefix used by Ed25519 public keys in X.509 encoding.
        val ed25519SpkiPrefix = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        val hasPrefix = spkiBytes.size == ed25519SpkiPrefix.size + 32 &&
            spkiBytes.copyOfRange(0, ed25519SpkiPrefix.size).contentEquals(ed25519SpkiPrefix)

        return if (hasPrefix) {
            spkiBytes.copyOfRange(ed25519SpkiPrefix.size, spkiBytes.size)
        } else {
            spkiBytes
        }
    }

    private fun loadOrCreateDeviceKeyPair(keyPath: Path): java.security.KeyPair {
        loadDeviceKeyPairFromEnv()?.let { return it }

        try {
            if (Files.exists(keyPath)) {
                val props = Properties()
                Files.newBufferedReader(keyPath).use { props.load(it) }
                val privateKeyEncoded = decodeBase64Flexible(props.getProperty("privateKey"))
                val publicKeyEncoded = decodeBase64Flexible(props.getProperty("publicKey"))
                val (publicKey, privateKey) = buildKeyPair(publicKeyEncoded, privateKeyEncoded)
                logger.info("Loaded persistent OpenClaw device identity from $keyPath")
                return java.security.KeyPair(publicKey, privateKey)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load persisted device identity, generating a new one: ${e.message}")
        }

        val generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        try {
            Files.createDirectories(keyPath.parent)
            val props = Properties().apply {
                setProperty("privateKey", Base64.getEncoder().encodeToString(generated.private.encoded))
                setProperty("publicKey", Base64.getEncoder().encodeToString(generated.public.encoded))
            }
            Files.newBufferedWriter(keyPath).use { props.store(it, "SuprClaw OpenClaw device identity") }
            logger.info("Generated and persisted OpenClaw device identity at $keyPath")
        } catch (e: Exception) {
            logger.warn("Generated device identity but failed to persist it: ${e.message}")
        }

        return generated
    }

    private fun loadDeviceKeyPairFromEnv(): java.security.KeyPair? {
        val privateKeyB64 = System.getenv("SUPRCLAW_DEVICE_PRIVATE_KEY_B64")?.trim()
        val publicKeyB64 = System.getenv("SUPRCLAW_DEVICE_PUBLIC_KEY_B64")?.trim()
        if (privateKeyB64.isNullOrEmpty() && publicKeyB64.isNullOrEmpty()) {
            return null
        }

        if (privateKeyB64.isNullOrEmpty() || publicKeyB64.isNullOrEmpty()) {
            throw IllegalStateException(
                "SUPRCLAW_DEVICE_PRIVATE_KEY_B64 and SUPRCLAW_DEVICE_PUBLIC_KEY_B64 must both be set together"
            )
        }

        try {
            val privateKeyEncoded = decodeBase64Flexible(privateKeyB64)
            val publicKeyEncoded = decodeBase64Flexible(publicKeyB64)
            val (publicKey, privateKey) = buildKeyPair(publicKeyEncoded, privateKeyEncoded)
            logger.info("Loaded OpenClaw device identity from environment variables")
            return java.security.KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            throw IllegalStateException("Invalid SUPRCLAW_DEVICE_* key material: ${e.message}", e)
        }
    }

    private fun decodeBase64Flexible(encoded: String): ByteArray {
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(encoded)
        }
    }

    private fun buildKeyPair(
        publicKeyEncoded: ByteArray,
        privateKeyEncoded: ByteArray
    ): Pair<java.security.PublicKey, java.security.PrivateKey> {
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyEncoded))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyEncoded))
        return Pair(publicKey, privateKey)
    }

    private fun resolveDeviceIdentityPath(): Path {
        val configuredPath = System.getenv("SUPRCLAW_DEVICE_IDENTITY_PATH")?.trim()
        if (!configuredPath.isNullOrEmpty()) {
            return Path.of(configuredPath)
        }

        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            return Path.of(userHome, ".suprclaw", "openclaw-device-identity.properties")
        }

        return Path.of(".", ".suprclaw", "openclaw-device-identity.properties")
    }

    private data class ConnectChallenge(
        val nonce: String? = null
    )
}
