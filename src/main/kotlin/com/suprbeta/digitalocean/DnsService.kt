package com.suprbeta.digitalocean

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import com.suprbeta.provider.DnsProvider
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DnsService(
    private val httpClient: HttpClient,
    private val application: Application
) : DnsProvider {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val apiToken = dotenv["DIGITALOCEAN_API_KEY"]
        ?: throw IllegalStateException("DIGITALOCEAN_API_KEY not found in environment")

    private val json = Json { ignoreUnknownKeys = true }

    val domain: String = dotenv["DOMAIN"]
        ?: throw IllegalStateException("DOMAIN not found in environment. Add DOMAIN=yourdomain.com to .env file")

    /**
     * Creates a DNS A record for a subdomain pointing to the given IP address
     * Deletes any existing records for the same subdomain first to prevent duplicates
     * @param subdomain The subdomain name (without the domain, e.g., "my-droplet")
     * @param ipAddress The IP address to point to
     * @return The full domain name (e.g., "my-droplet.yourdomain.com")
     */
    suspend fun createDnsRecord(subdomain: String, ipAddress: String): String {
        application.log.info("Creating DNS record: $subdomain.$domain -> $ipAddress")

        try {
            // First, delete any existing DNS records for this subdomain to prevent duplicates
            deleteExistingRecords(subdomain)

            // Now create the new DNS record
            val request = CreateDnsRecordRequest(
                type = "A",
                name = subdomain,
                data = ipAddress,
                ttl = 300 // 5 minutes TTL for quick updates
            )

            val response: HttpResponse = httpClient.post("https://api.digitalocean.com/v2/domains/$domain/records") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiToken")
                setBody(request)
            }

            application.log.info("DNS record created: ${response.status}")

            if (response.status.isSuccess()) {
                val fullDomain = "$subdomain.$domain"
                application.log.info("✅ DNS record created: $fullDomain -> $ipAddress")
                return fullDomain
            } else {
                val errorBody = response.bodyAsText()
                application.log.error("Failed to create DNS record: ${response.status} - $errorBody")
                throw IllegalStateException("Failed to create DNS record: ${response.status}")
            }
        } catch (e: Exception) {
            application.log.error("Error creating DNS record", e)
            throw e
        }
    }

    /**
     * Returns all A record IDs for the given subdomain. Uses per_page=200 to avoid
     * pagination issues on domains with many records.
     */
    private suspend fun findRecordIds(subdomain: String): List<String> {
        val response: HttpResponse = httpClient.get(
            "https://api.digitalocean.com/v2/domains/$domain/records?type=A&per_page=200"
        ) {
            header("Authorization", "Bearer $apiToken")
        }
        if (!response.status.isSuccess()) return emptyList()

        val body = response.bodyAsText()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["domain_records"]
                ?.jsonArray
                ?.filter { it.jsonObject["name"]?.jsonPrimitive?.content == subdomain }
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?: emptyList()
        }.getOrElse {
            application.log.warn("Failed to parse DNS records response: ${it.message}")
            emptyList()
        }
    }

    /** Deletes all existing A records for a subdomain to prevent duplicate entries. */
    private suspend fun deleteExistingRecords(subdomain: String) {
        try {
            val ids = findRecordIds(subdomain)
            var deletedCount = 0
            ids.forEach { id ->
                try {
                    val resp = httpClient.delete(
                        "https://api.digitalocean.com/v2/domains/$domain/records/$id"
                    ) { header("Authorization", "Bearer $apiToken") }
                    if (resp.status.isSuccess()) {
                        deletedCount++
                        application.log.info("Deleted old DNS record $id for $subdomain")
                    }
                } catch (e: Exception) {
                    application.log.warn("Failed to delete DNS record $id: ${e.message}")
                }
            }
            if (deletedCount > 0) {
                application.log.info("✅ Deleted $deletedCount old DNS record(s) for $subdomain")
            }
        } catch (e: Exception) {
            application.log.warn("Error checking/deleting existing DNS records: ${e.message}")
        }
    }

    /** Deletes all A records for a subdomain (called during teardown). */
    suspend fun deleteDnsRecord(subdomain: String) {
        application.log.info("Deleting DNS record: $subdomain.$domain")
        try {
            val ids = findRecordIds(subdomain)
            if (ids.isEmpty()) {
                application.log.info("No DNS records found for $subdomain")
                return
            }
            ids.forEach { id ->
                val resp = httpClient.delete(
                    "https://api.digitalocean.com/v2/domains/$domain/records/$id"
                ) { header("Authorization", "Bearer $apiToken") }
                application.log.info("Deleted DNS record $id for $subdomain: ${resp.status}")
            }
            application.log.info("✅ DNS record(s) deleted for $subdomain.$domain")
        } catch (e: Exception) {
            application.log.warn("Error deleting DNS record for $subdomain: ${e.message}")
        }
    }
}

@Serializable
data class CreateDnsRecordRequest(
    val type: String,
    val name: String,
    val data: String,
    val ttl: Int
)
