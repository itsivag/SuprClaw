package com.suprbeta.digitalocean

import com.suprbeta.digitalocean.models.CreateDropletRequest
import com.suprbeta.digitalocean.models.DropletResponse
import com.suprbeta.provider.VpsService
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*

class DigitalOceanService(
    private val httpClient: HttpClient,
    private val application: Application
) : VpsService {
    companion object {
        private const val API_BASE_URL = "https://api.digitalocean.com/v2/droplets"
        private const val DEFAULT_SIZE = "s-1vcpu-2gb"
        private const val DEFAULT_REGION = "sfo2"
        private const val DEFAULT_IMAGE = "218575988" //v0.9
        private const val DEFAULT_VPC_UUID = "e6d1d295-29b8-469f-ac55-660f20ba61cf"
        private const val DEFAULT_MONITORING = true
    }

    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val apiToken = dotenv["DIGITALOCEAN_API_KEY"]
        ?: throw IllegalStateException("DIGITALOCEAN_API_KEY not found in environment")

    val geminiApiKey: String = dotenv["GEMINI_API_KEY"]
        ?: throw IllegalStateException("GEMINI_API_KEY not found in environment")

    // SSH keys to add to droplets (prevents password email from DigitalOcean)
    // Can be SSH key IDs or fingerprints, comma-separated
    private val sshKeys: List<String>? = dotenv["DIGITALOCEAN_SSH_KEYS"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }

    /**
     * Creates a droplet with minimal bootstrap user-data (only creates openclaw user).
     * The password is templated into cloud-config for SSH access during onboarding.
     */
    suspend fun createDroplet(name: String, password: String): DropletResponse {
        application.log.info("Creating DigitalOcean droplet: $name")

        val userData = UserDataGenerator.generateBootstrapUserData(password)

        val request = CreateDropletRequest(
            name = name,
            size = DEFAULT_SIZE,
            region = DEFAULT_REGION,
            image = DEFAULT_IMAGE,
            monitoring = DEFAULT_MONITORING,
            vpc_uuid = DEFAULT_VPC_UUID,
            user_data = userData,
            ssh_keys = sshKeys // Add SSH keys to prevent password email
        )

        if (sshKeys != null) {
            application.log.info("Creating droplet with ${sshKeys.size} SSH key(s) - password email will be disabled")
        } else {
            application.log.warn("No SSH keys configured - DigitalOcean will send password email")
        }

        val response: HttpResponse = httpClient.post(API_BASE_URL) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
            setBody(request)
        }

        application.log.info("DigitalOcean API response status: ${response.status}")

        return response.body()
    }

    /**
     * Polls a single droplet by ID to check its status and retrieve its IP.
     */
    suspend fun getDroplet(dropletId: Long): DropletResponse {
        val response: HttpResponse = httpClient.get("$API_BASE_URL/$dropletId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
        }

        return response.body()
    }

    /**
     * Deletes a droplet by ID.
     */
    suspend fun deleteDroplet(dropletId: Long) {
        application.log.info("Deleting DigitalOcean droplet: $dropletId")
        httpClient.delete("$API_BASE_URL/$dropletId") {
            header("Authorization", "Bearer $apiToken")
        }
    }

    // ── VpsService implementation ────────────────────────────────────────────

    override suspend fun createServer(name: String, password: String): VpsService.ServerCreateResult {
        val response = createDroplet(name, password)
        val dropletId = response.droplet?.id
            ?: throw IllegalStateException("DigitalOcean did not return a droplet ID")
        return VpsService.ServerCreateResult(serverId = dropletId)
    }

    override suspend fun getServer(serverId: Long): VpsService.ServerInfo {
        val response = getDroplet(serverId)
        val droplet = response.droplet
        val ip = droplet?.networks?.v4?.firstOrNull { it.type == "public" }?.ip_address
        return VpsService.ServerInfo(status = droplet?.status ?: "unknown", publicIpV4 = ip)
    }

    override suspend fun deleteServer(serverId: Long) = deleteDroplet(serverId)
}
