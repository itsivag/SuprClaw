package com.suprbeta.hetzner

import com.suprbeta.infra.DnsProvider
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hetzner DNS implementation of [DnsProvider].
 *
 * Required env vars:
 *   HETZNER_DNS_TOKEN – API token from dns.hetzner.com (separate from the Cloud token)
 *   DOMAIN            – root domain managed in Hetzner DNS, e.g. "suprclaw.com"
 *
 * The domain must already exist as a zone in your Hetzner DNS account.
 */
class HetznerDnsProvider(
    private val httpClient: HttpClient,
    private val application: Application
) : DnsProvider {

    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private val dnsToken = dotenv["HETZNER_DNS_TOKEN"]
        ?: throw IllegalStateException("HETZNER_DNS_TOKEN not set")

    override val domain: String = dotenv["DOMAIN"]
        ?: throw IllegalStateException("DOMAIN not set. Add DOMAIN=yourdomain.com to .env")

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://dns.hetzner.com/api/v1"

    override suspend fun createRecord(subdomain: String, ipAddress: String): String {
        application.log.info("Creating Hetzner DNS A record: $subdomain.$domain -> $ipAddress")

        val zoneId = resolveZoneId()
        deleteExistingRecords(subdomain, zoneId)

        val response = httpClient.post("$baseUrl/records") {
            contentType(ContentType.Application.Json)
            header("Auth-API-Token", dnsToken)
            setBody(HetznerDnsRecordRequest(zone_id = zoneId, type = "A", name = subdomain, value = ipAddress, ttl = 300))
        }

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw IllegalStateException("Failed to create Hetzner DNS record: ${response.status} – $body")
        }

        val fullDomain = "$subdomain.$domain"
        application.log.info("✅ Hetzner DNS record created: $fullDomain -> $ipAddress")
        return fullDomain
    }

    override suspend fun deleteRecord(subdomain: String) {
        application.log.info("Deleting Hetzner DNS record: $subdomain.$domain")
        val zoneId = resolveZoneId()
        findRecordIds(subdomain, zoneId).forEach { id ->
            httpClient.delete("$baseUrl/records/$id") {
                header("Auth-API-Token", dnsToken)
            }
            application.log.info("Deleted Hetzner DNS record $id for $subdomain")
        }
    }

    /** Looks up the Hetzner zone ID for [domain]. Throws if not found. */
    private suspend fun resolveZoneId(): String {
        val response = httpClient.get("$baseUrl/zones?name=$domain") {
            header("Auth-API-Token", dnsToken)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to list Hetzner DNS zones: ${response.status}")
        }

        val zoneId = runCatching {
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject["zones"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject?.get("id")
                ?.jsonPrimitive?.content
        }.getOrNull()
            ?: throw IllegalStateException("Zone '$domain' not found in Hetzner DNS. Create it first.")

        return zoneId
    }

    private suspend fun findRecordIds(subdomain: String, zoneId: String): List<String> {
        val response = httpClient.get("$baseUrl/records?zone_id=$zoneId") {
            header("Auth-API-Token", dnsToken)
        }
        if (!response.status.isSuccess()) return emptyList()

        return runCatching {
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject["records"]
                ?.jsonArray
                ?.filter {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "A" &&
                    it.jsonObject["name"]?.jsonPrimitive?.content == subdomain
                }
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private suspend fun deleteExistingRecords(subdomain: String, zoneId: String) {
        findRecordIds(subdomain, zoneId).forEach { id ->
            runCatching {
                httpClient.delete("$baseUrl/records/$id") {
                    header("Auth-API-Token", dnsToken)
                }
                application.log.info("Removed stale Hetzner DNS record $id for $subdomain")
            }.onFailure { application.log.warn("Could not delete Hetzner DNS record $id: ${it.message}") }
        }
    }
}

@Serializable
private data class HetznerDnsRecordRequest(
    val zone_id: String,
    val type: String,
    val name: String,
    val value: String,
    val ttl: Int
)
