package com.suprbeta.digitalocean

import com.suprbeta.infra.VpsProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

/**
 * DigitalOcean implementation of [VpsProvider].
 *
 * Required env vars:
 *   DIGITALOCEAN_API_KEY   – personal access token
 *   DO_SNAPSHOT_ID         – base image snapshot ID (defaults to "218575988")
 *   DO_REGION              – datacenter slug      (defaults to "sfo2")
 *   DO_SIZE                – droplet size slug    (defaults to "s-1vcpu-2gb")
 *   DO_VPC_UUID            – optional VPC UUID
 *   DIGITALOCEAN_SSH_KEYS  – optional comma-separated SSH key IDs/fingerprints
 */
class DigitalOceanVpsProvider(
    private val httpClient: HttpClient,
    private val application: Application
) : VpsProvider {

    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private val apiToken = dotenv["DIGITALOCEAN_API_KEY"]
        ?: throw IllegalStateException("DIGITALOCEAN_API_KEY not set")

    private val snapshotId = dotenv["DO_SNAPSHOT_ID"] ?: "218575988"
    private val region     = dotenv["DO_REGION"]      ?: "sfo2"
    private val size       = dotenv["DO_SIZE"]        ?: "s-1vcpu-2gb"
    private val vpcUuid    = dotenv["DO_VPC_UUID"]

    private val sshKeys: List<String>? = dotenv["DIGITALOCEAN_SSH_KEYS"]
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

    private val baseUrl = "https://api.digitalocean.com/v2/droplets"

    override suspend fun create(name: String, password: String): VpsProvider.VpsCreateResult {
        application.log.info("Creating DigitalOcean droplet: $name (region=$region, size=$size)")

        val userData = UserDataGenerator.generateBootstrapUserData(password)

        val body = DoCreateServerRequest(
            name = name,
            size = size,
            region = region,
            image = snapshotId,
            monitoring = true,
            vpc_uuid = vpcUuid,
            user_data = userData,
            ssh_keys = sshKeys
        )

        val response: DoDropletEnvelope = httpClient.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
            setBody(body)
        }.body()

        val id = response.droplet?.id
            ?: throw IllegalStateException("DigitalOcean did not return a droplet ID")

        application.log.info("DigitalOcean droplet created: id=$id")
        return VpsProvider.VpsCreateResult(id)
    }

    override suspend fun getState(id: Long): VpsProvider.VpsState {
        val response: DoDropletEnvelope = httpClient.get("$baseUrl/$id") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
        }.body()

        val droplet = response.droplet
        val ip = droplet?.networks?.v4
            ?.firstOrNull { it.type == "public" }
            ?.ip_address

        // DO status "active" is already what we use as the canonical "ready" value
        return VpsProvider.VpsState(status = droplet?.status ?: "unknown", publicIpv4 = ip)
    }

    override suspend fun delete(id: Long) {
        application.log.info("Deleting DigitalOcean droplet: $id")
        httpClient.delete("$baseUrl/$id") {
            header("Authorization", "Bearer $apiToken")
        }
    }
}

// ── Internal request / response models ──────────────────────────────────────

@Serializable
private data class DoCreateServerRequest(
    val name: String,
    val size: String,
    val region: String,
    val image: String,
    val monitoring: Boolean,
    val vpc_uuid: String? = null,
    val user_data: String? = null,
    val ssh_keys: List<String>? = null
)

@Serializable
private data class DoDropletEnvelope(
    val droplet: DoDroplet? = null
)

@Serializable
private data class DoDroplet(
    val id: Long? = null,
    val status: String? = null,
    val networks: DoNetworks? = null
)

@Serializable
private data class DoNetworks(
    val v4: List<DoNetworkV4>? = null
)

@Serializable
private data class DoNetworkV4(
    val ip_address: String? = null,
    val type: String? = null
)
