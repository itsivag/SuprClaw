package com.suprbeta.digitalocean

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
 * DigitalOcean implementation of [DnsProvider].
 *
 * Required env vars:
 *   DIGITALOCEAN_API_KEY – personal access token (same as for compute)
 *   DOMAIN               – root domain managed in DO, e.g. "suprclaw.com"
 */
class DigitalOceanDnsProvider(
    private val httpClient: HttpClient,
    private val application: Application
) : DnsProvider {

    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    private val apiToken = dotenv["DIGITALOCEAN_API_KEY"]
        ?: throw IllegalStateException("DIGITALOCEAN_API_KEY not set")

    override val domain: String = dotenv["DOMAIN"]
        ?: throw IllegalStateException("DOMAIN not set. Add DOMAIN=yourdomain.com to .env")

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl get() = "https://api.digitalocean.com/v2/domains/$domain/records"

    override suspend fun createRecord(subdomain: String, ipAddress: String): String {
        application.log.info("Creating DNS A record: $subdomain.$domain -> $ipAddress")

        deleteExistingRecords(subdomain)

        val response = httpClient.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiToken")
            setBody(DoDnsRecordRequest(type = "A", name = subdomain, data = ipAddress, ttl = 300))
        }

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw IllegalStateException("Failed to create DNS record: ${response.status} – $body")
        }

        val fullDomain = "$subdomain.$domain"
        application.log.info("✅ DNS record created: $fullDomain -> $ipAddress")
        return fullDomain
    }

    override suspend fun deleteRecord(subdomain: String) {
        application.log.info("Deleting DNS record: $subdomain.$domain")
        findRecordIds(subdomain).forEach { id ->
            httpClient.delete("$baseUrl/$id") {
                header("Authorization", "Bearer $apiToken")
            }
            application.log.info("Deleted DNS record $id for $subdomain")
        }
    }

    private suspend fun deleteExistingRecords(subdomain: String) {
        val ids = findRecordIds(subdomain)
        ids.forEach { id ->
            runCatching {
                httpClient.delete("$baseUrl/$id") {
                    header("Authorization", "Bearer $apiToken")
                }
                application.log.info("Removed stale DNS record $id for $subdomain")
            }.onFailure { application.log.warn("Could not delete DNS record $id: ${it.message}") }
        }
    }

    private suspend fun findRecordIds(subdomain: String): List<String> {
        val response = httpClient.get("$baseUrl?type=A&per_page=200") {
            header("Authorization", "Bearer $apiToken")
        }
        if (!response.status.isSuccess()) return emptyList()

        return runCatching {
            json.parseToJsonElement(response.bodyAsText())
                .jsonObject["domain_records"]
                ?.jsonArray
                ?.filter { it.jsonObject["name"]?.jsonPrimitive?.content == subdomain }
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }
}

@Serializable
private data class DoDnsRecordRequest(
    val type: String,
    val name: String,
    val data: String,
    val ttl: Int
)
