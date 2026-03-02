package com.suprbeta.hetzner

import com.suprbeta.hetzner.models.HetznerCreateRRsetRequest
import com.suprbeta.hetzner.models.HetznerCreateRRsetResponse
import com.suprbeta.hetzner.models.HetznerRRsetRecord
import com.suprbeta.hetzner.models.HetznerRRsetsResponse
import com.suprbeta.hetzner.models.HetznerZonesResponse
import com.suprbeta.provider.DnsProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*

/**
 * Hetzner DNS provider using the Cloud API (api.hetzner.cloud/v1).
 *
 * Required environment variables:
 *   HETZNER_API_TOKEN  — Hetzner Cloud API token (same token used for compute)
 *   DOMAIN             — Root domain managed in Hetzner DNS (e.g. "example.com")
 */
class HetznerDnsService(
    private val httpClient: HttpClient,
    private val application: Application
) : DnsProvider {

    companion object {
        private const val DNS_BASE_URL = "https://api.hetzner.cloud/v1"
    }

    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val dnsToken: String = dotenv["HETZNER_API_TOKEN"]
        ?: throw IllegalStateException("HETZNER_API_TOKEN not found in environment")

    private val domain: String = dotenv["DOMAIN"]
        ?: throw IllegalStateException("DOMAIN not found in environment")

    /** Cached zone ID — resolved once on first use. */
    private var zoneId: Long? = null

    private suspend fun resolveZoneId(): Long {
        zoneId?.let { return it }

        val response: HetznerZonesResponse = httpClient.get("$DNS_BASE_URL/zones") {
            parameter("name", domain)
            header("Authorization", "Bearer $dnsToken")
        }.body()

        val id = response.zones?.firstOrNull()?.id
            ?: throw IllegalStateException("Hetzner DNS zone not found for domain: $domain")

        zoneId = id
        return id
    }

    override suspend fun createDnsRecord(subdomain: String, ipAddress: String): String {
        application.log.info("Creating Hetzner DNS record: $subdomain.$domain -> $ipAddress")

        val zId = resolveZoneId()

        // Delete any existing A records for this subdomain first
        deleteExistingRecords(subdomain, zId)

        val request = HetznerCreateRRsetRequest(
            name = subdomain,
            type = "A",
            records = listOf(HetznerRRsetRecord(value = ipAddress)),
            ttl = 300
        )

        val response: HetznerCreateRRsetResponse = httpClient.post("$DNS_BASE_URL/zones/$zId/rrsets") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $dnsToken")
            setBody(request)
        }.body()

        response.rrset?.id
            ?: throw IllegalStateException("Hetzner DNS did not return an RRset ID")

        val fullDomain = "$subdomain.$domain"
        application.log.info("Hetzner DNS record created: $fullDomain -> $ipAddress")
        return fullDomain
    }

    override suspend fun deleteDnsRecord(subdomain: String) {
        application.log.info("Deleting Hetzner DNS record: $subdomain.$domain")
        try {
            val zId = resolveZoneId()
            httpClient.delete("$DNS_BASE_URL/zones/$zId/rrsets/$subdomain/A") {
                header("Authorization", "Bearer $dnsToken")
            }
            application.log.info("Deleted Hetzner DNS record: $subdomain.$domain")
        } catch (e: Exception) {
            application.log.warn("Error deleting Hetzner DNS record for $subdomain: ${e.message}")
        }
    }

    private suspend fun deleteExistingRecords(subdomain: String, zoneId: Long) {
        try {
            val response: HetznerRRsetsResponse = httpClient.get("$DNS_BASE_URL/zones/$zoneId/rrsets") {
                parameter("type", "A")
                header("Authorization", "Bearer $dnsToken")
            }.body()

            val exists = response.rrsets?.any { it.name == subdomain } ?: false
            if (exists) {
                httpClient.delete("$DNS_BASE_URL/zones/$zoneId/rrsets/$subdomain/A") {
                    header("Authorization", "Bearer $dnsToken")
                }
                application.log.info("Deleted existing Hetzner DNS record for $subdomain")
            }
        } catch (e: Exception) {
            application.log.warn("Error checking/deleting existing Hetzner DNS record for $subdomain: ${e.message}")
        }
    }
}
