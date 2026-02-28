package com.suprbeta.hetzner

import com.suprbeta.digitalocean.UserDataGenerator
import com.suprbeta.infra.VpsProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

/**
 * Hetzner Cloud implementation of [VpsProvider].
 *
 * Required env vars:
 *   HETZNER_API_TOKEN    – API token from console.hetzner.cloud
 *   HETZNER_SNAPSHOT_ID  – server snapshot ID containing the OpenClaw base image
 *
 * Optional env vars (with defaults):
 *   HETZNER_SERVER_TYPE  – server type slug (default "cx22" = 2 vCPU / 4 GB)
 *   HETZNER_LOCATION     – datacenter location (default "nbg1" = Nuremberg)
 *   HETZNER_SSH_KEY      – SSH key name or ID to inject (prevents root password email)
 *
 * Hetzner server status lifecycle:
 *   initializing → starting → running (mapped to our canonical "active")
 */
class HetznerVpsProvider(
    private val httpClient: HttpClient,
    private val application: Application
) : VpsProvider {

    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private val apiToken = dotenv["HETZNER_API_TOKEN"]
        ?: throw IllegalStateException("HETZNER_API_TOKEN not set")

    private val snapshotId = dotenv["HETZNER_SNAPSHOT_ID"]
        ?: throw IllegalStateException("HETZNER_SNAPSHOT_ID not set")

    private val serverType = dotenv["HETZNER_SERVER_TYPE"] ?: "cx22"
    private val location   = dotenv["HETZNER_LOCATION"]   ?: "nbg1"
    private val sshKey     = dotenv["HETZNER_SSH_KEY"]

    private val baseUrl = "https://api.hetzner.cloud/v1/servers"

    override suspend fun create(name: String, password: String): VpsProvider.VpsCreateResult {
        application.log.info("Creating Hetzner server: $name (type=$serverType, location=$location)")

        val userData = UserDataGenerator.generateBootstrapUserData(password)

        val body = HetznerCreateServerRequest(
            name = name,
            server_type = serverType,
            image = snapshotId,
            location = location,
            user_data = userData,
            ssh_keys = sshKey?.let { listOf(it) }
        )

        val response: HetznerCreateServerEnvelope = httpClient.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
            setBody(body)
        }.body()

        val id = response.server?.id
            ?: throw IllegalStateException("Hetzner did not return a server ID")

        application.log.info("Hetzner server created: id=$id")
        return VpsProvider.VpsCreateResult(id)
    }

    override suspend fun getState(id: Long): VpsProvider.VpsState {
        val response: HetznerGetServerEnvelope = httpClient.get("$baseUrl/$id") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
        }.body()

        val server = response.server
        val ip = server?.public_net?.ipv4?.ip

        // Normalize "running" → "active" so the provisioning loop works the same
        // regardless of provider
        val status = when (server?.status) {
            "running" -> "active"
            else      -> server?.status ?: "unknown"
        }

        return VpsProvider.VpsState(status = status, publicIpv4 = ip)
    }

    override suspend fun delete(id: Long) {
        application.log.info("Deleting Hetzner server: $id")
        httpClient.delete("$baseUrl/$id") {
            header("Authorization", "Bearer $apiToken")
        }
    }
}

// ── Internal request / response models ──────────────────────────────────────

@Serializable
private data class HetznerCreateServerRequest(
    val name: String,
    val server_type: String,
    val image: String,
    val location: String,
    val user_data: String? = null,
    val ssh_keys: List<String>? = null
)

@Serializable
private data class HetznerCreateServerEnvelope(
    val server: HetznerServer? = null
)

@Serializable
private data class HetznerGetServerEnvelope(
    val server: HetznerServerDetail? = null
)

@Serializable
private data class HetznerServer(
    val id: Long? = null,
    val name: String? = null,
    val status: String? = null
)

@Serializable
private data class HetznerServerDetail(
    val id: Long? = null,
    val status: String? = null,
    val public_net: HetznerPublicNet? = null
)

@Serializable
private data class HetznerPublicNet(
    val ipv4: HetznerIpv4? = null
)

@Serializable
private data class HetznerIpv4(
    val ip: String? = null
)
