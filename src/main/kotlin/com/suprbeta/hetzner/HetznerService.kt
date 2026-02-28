package com.suprbeta.hetzner

import com.suprbeta.digitalocean.UserDataGenerator
import com.suprbeta.hetzner.models.CreateServerRequest
import com.suprbeta.hetzner.models.CreateServerResponse
import com.suprbeta.hetzner.models.GetServerResponse
import com.suprbeta.hetzner.models.PublicNetConfig
import com.suprbeta.provider.VpsService
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*

/**
 * Hetzner Cloud VPS provider.
 *
 * Required environment variables:
 *   HETZNER_API_TOKEN       — Hetzner Cloud API token
 *
 * Optional environment variables:
 *   HETZNER_IMAGE           — Server image name or snapshot ID (default: ubuntu-22.04)
 *   HETZNER_SERVER_TYPE     — Server type slug (default: cx22 ≈ 2 vCPU / 4 GB RAM)
 *   HETZNER_LOCATION        — Datacenter location code (default: ash = Ashburn, US)
 *   HETZNER_SSH_KEY_IDS     — Comma-separated numeric SSH key IDs registered in Hetzner
 */
class HetznerService(
    private val httpClient: HttpClient,
    private val application: Application
) : VpsService {

    companion object {
        private const val SERVERS_URL = "https://api.hetzner.cloud/v1/servers"
        private const val DEFAULT_SERVER_TYPE = "cx22"
        private const val DEFAULT_LOCATION = "ash"
        private const val DEFAULT_IMAGE = "ubuntu-22.04"
    }

    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val apiToken: String = dotenv["HETZNER_API_TOKEN"]
        ?: throw IllegalStateException("HETZNER_API_TOKEN not found in environment")

    private val image: String = dotenv["HETZNER_IMAGE"] ?: DEFAULT_IMAGE
    private val serverType: String = dotenv["HETZNER_SERVER_TYPE"] ?: DEFAULT_SERVER_TYPE
    private val location: String = dotenv["HETZNER_LOCATION"] ?: DEFAULT_LOCATION

    private val sshKeys: List<Long>? = dotenv["HETZNER_SSH_KEY_IDS"]
        ?.split(",")
        ?.mapNotNull { it.trim().toLongOrNull() }
        ?.takeIf { it.isNotEmpty() }

    override suspend fun createServer(name: String, password: String): VpsService.ServerCreateResult {
        application.log.info("Creating Hetzner server: $name (type=$serverType, location=$location, image=$image)")

        val userData = UserDataGenerator.generateBootstrapUserData(password)

        val request = CreateServerRequest(
            name = name,
            server_type = serverType,
            image = image,
            location = location,
            user_data = userData,
            ssh_keys = sshKeys,
            public_net = PublicNetConfig(enable_ipv4 = true, enable_ipv6 = false)
        )

        if (sshKeys != null) {
            application.log.info("Creating server with ${sshKeys.size} SSH key(s)")
        }

        val response: CreateServerResponse = httpClient.post(SERVERS_URL) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
            setBody(request)
        }.body()

        val serverId = response.server?.id
            ?: throw IllegalStateException("Hetzner did not return a server ID")

        application.log.info("Hetzner server $serverId created, waiting for it to become active...")
        return VpsService.ServerCreateResult(serverId = serverId)
    }

    override suspend fun getServer(serverId: Long): VpsService.ServerInfo {
        val response: GetServerResponse = httpClient.get("$SERVERS_URL/$serverId") {
            header("Authorization", "Bearer $apiToken")
        }.body()

        val server = response.server
        val ip = server?.public_net?.ipv4?.ip

        // Normalize Hetzner status to canonical "active" used by DropletProvisioningService
        val normalizedStatus = when (server?.status) {
            "running" -> "active"
            "initializing", "starting" -> "starting"
            else -> server?.status ?: "unknown"
        }

        return VpsService.ServerInfo(status = normalizedStatus, publicIpV4 = ip)
    }

    override suspend fun deleteServer(serverId: Long) {
        application.log.info("Deleting Hetzner server: $serverId")
        httpClient.delete("$SERVERS_URL/$serverId") {
            header("Authorization", "Bearer $apiToken")
        }
    }
}
