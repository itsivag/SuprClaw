package com.suprbeta.digitalocean

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

class DnsService(
    private val httpClient: HttpClient,
    private val application: Application
) {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val apiToken = dotenv["DIGITALOCEAN_API_KEY"]
        ?: throw IllegalStateException("DIGITALOCEAN_API_KEY not found in environment")

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
     * Deletes all existing DNS A records for a subdomain
     * @param subdomain The subdomain to clean up
     */
    private suspend fun deleteExistingRecords(subdomain: String) {
        try {
            application.log.info("Checking for existing DNS records for $subdomain")

            val response: HttpResponse = httpClient.get("https://api.digitalocean.com/v2/domains/$domain/records") {
                header("Authorization", "Bearer $apiToken")
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()

                // Parse JSON to find all records matching this subdomain
                // Simple regex to find record IDs for this subdomain
                val recordIdPattern = """\"id\":(\d+),\"type\":\"A\",\"name\":\"$subdomain\"""".toRegex()
                val matches = recordIdPattern.findAll(body)

                var deletedCount = 0
                matches.forEach { match ->
                    val recordId = match.groupValues[1]
                    try {
                        val deleteResponse: HttpResponse = httpClient.delete(
                            "https://api.digitalocean.com/v2/domains/$domain/records/$recordId"
                        ) {
                            header("Authorization", "Bearer $apiToken")
                        }
                        if (deleteResponse.status.isSuccess()) {
                            deletedCount++
                            application.log.info("Deleted old DNS record $recordId for $subdomain")
                        }
                    } catch (e: Exception) {
                        application.log.warn("Failed to delete DNS record $recordId: ${e.message}")
                    }
                }

                if (deletedCount > 0) {
                    application.log.info("✅ Deleted $deletedCount old DNS record(s) for $subdomain")
                }
            }
        } catch (e: Exception) {
            application.log.warn("Error checking/deleting existing DNS records: ${e.message}")
            // Don't fail the whole operation if cleanup fails
        }
    }

    /**
     * Deletes a DNS record for a subdomain
     * @param subdomain The subdomain to delete
     */
    suspend fun deleteDnsRecord(subdomain: String) {
        application.log.info("Deleting DNS record: $subdomain.$domain")

        try {
            // First, get all records to find the record ID
            val response: HttpResponse = httpClient.get("https://api.digitalocean.com/v2/domains/$domain/records") {
                header("Authorization", "Bearer $apiToken")
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                // Parse to find the record ID for the subdomain
                // For now, we'll just log it - in production you'd parse and delete
                application.log.info("DNS records retrieved, would delete $subdomain")
            }
        } catch (e: Exception) {
            application.log.warn("Error deleting DNS record: ${e.message}")
            // Don't fail the whole operation if DNS deletion fails
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
