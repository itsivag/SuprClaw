package com.suprbeta.infra

/**
 * Provider-agnostic interface for DNS A-record management.
 * Implementations: DigitalOceanDnsProvider, HetznerDnsProvider
 */
interface DnsProvider {

    /** The root domain managed by this provider (e.g. "suprclaw.com"). */
    val domain: String

    /**
     * Create an A record pointing [subdomain] at [ipAddress].
     * Any pre-existing A records for the same subdomain are removed first.
     * @return The fully-qualified domain name, e.g. "bob.suprclaw.com"
     */
    suspend fun createRecord(subdomain: String, ipAddress: String): String

    /** Delete all A records for [subdomain] (called during teardown). */
    suspend fun deleteRecord(subdomain: String)
}
