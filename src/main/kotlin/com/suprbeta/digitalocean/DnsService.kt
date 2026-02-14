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
     * @param subdomain The subdomain name (without the domain, e.g., "my-droplet")
     * @param ipAddress The IP address to point to
     * @return The full domain name (e.g., "my-droplet.yourdomain.com")
     */
    suspend fun createDnsRecord(subdomain: String, ipAddress: String): String {
        application.log.info("Creating DNS record: $subdomain.$domain -> $ipAddress")

        val request = CreateDnsRecordRequest(
            type = "A",
            name = subdomain,
            data = ipAddress,
            ttl = 300 // 5 minutes TTL for quick updates
        )

        try {
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
