package com.suprbeta.provider

/**
 * DNS provider abstraction for managing A records on behalf of provisioned servers.
 * Implementations include DigitalOceanDnsAdapter and HetznerDnsService.
 */
interface DnsProvider {
    /**
     * Creates an A record pointing [subdomain] to [ipAddress].
     * @return the fully-qualified domain name (e.g. "my-server.example.com")
     */
    suspend fun createDnsRecord(subdomain: String, ipAddress: String): String

    /**
     * Deletes all A records for the given [subdomain].
     */
    suspend fun deleteDnsRecord(subdomain: String)
}
