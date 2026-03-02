package com.suprbeta.hetzner

import com.suprbeta.hetzner.models.HetznerCreateDnsRecordRequest
import com.suprbeta.hetzner.models.HetznerCreateDnsRecordResponse
import com.suprbeta.hetzner.models.HetznerDnsRecordsResponse
import com.suprbeta.hetzner.models.HetznerZonesResponse
import com.suprbeta.provider.DnsProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*

/**
 * Hetzner DNS provider.
 *
 * Required environment variables:
 *   HETZNER_API_TOKEN  — Hetzner API token (shared with HetznerService)
 *   DOMAIN             — Root domain managed in Hetzner DNS (e.g. "example.com")
 */
class HetznerDnsService(
    private val httpClient: HttpClient,
    private val application: Application
) : DnsProvider {

    companion object {
        private const val DNS_BASE_URL = "https://dns.hetzner.com/api/v1"
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
    private var zoneId: String? = null

    private suspend fun resolveZoneId(): String {
        zoneId?.let { return it }

        val response: HetznerZonesResponse = httpClient.get("$DNS_BASE_URL/zones") {
            parameter("name", domain)
            header("Auth-API-Token", dnsToken)
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

        val request = HetznerCreateDnsRecordRequest(
            zone_id = zId,
            type = "A",
            name = subdomain,
            value = ipAddress,
            ttl = 300
        )

        val response: HetznerCreateDnsRecordResponse = httpClient.post("$DNS_BASE_URL/records") {
            contentType(ContentType.Application.Json)
            header("Auth-API-Token", dnsToken)
            setBody(request)
        }.body()

        response.record?.id
            ?: throw IllegalStateException("Hetzner DNS did not return a record ID")

        val fullDomain = "$subdomain.$domain"
        application.log.info("Hetzner DNS record created: $fullDomain -> $ipAddress")
        return fullDomain
    }

    override suspend fun deleteDnsRecord(subdomain: String) {
        application.log.info("Deleting Hetzner DNS record: $subdomain.$domain")
        try {
            val zId = resolveZoneId()
            deleteExistingRecords(subdomain, zId)
        } catch (e: Exception) {
            application.log.warn("Error deleting Hetzner DNS record for $subdomain: ${e.message}")
        }
    }

    private suspend fun deleteExistingRecords(subdomain: String, zoneId: String) {
        try {
            val response: HetznerDnsRecordsResponse = httpClient.get("$DNS_BASE_URL/records") {
                parameter("zone_id", zoneId)
                header("Auth-API-Token", dnsToken)
            }.body()

            val matchingIds = response.records
                ?.filter { it.type == "A" && it.name == subdomain }
                ?.mapNotNull { it.id }
                ?: emptyList()

            matchingIds.forEach { id ->
                try {
                    httpClient.delete("$DNS_BASE_URL/records/$id") {
                        header("Auth-API-Token", dnsToken)
                    }
                    application.log.info("Deleted Hetzner DNS record $id for $subdomain")
                } catch (e: Exception) {
                    application.log.warn("Failed to delete Hetzner DNS record $id: ${e.message}")
                }
            }
        } catch (e: Exception) {
            application.log.warn("Error listing Hetzner DNS records for $subdomain: ${e.message}")
        }
    }
}
