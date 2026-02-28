package com.suprbeta.hetzner.models

import kotlinx.serialization.Serializable

// ── Hetzner Cloud (compute) models ──────────────────────────────────────────

@Serializable
data class CreateServerRequest(
    val name: String,
    val server_type: String,
    val image: String,
    val location: String,
    val user_data: String? = null,
    val ssh_keys: List<Long>? = null,
    val public_net: PublicNetConfig? = null
)

@Serializable
data class PublicNetConfig(
    val enable_ipv4: Boolean = true,
    val enable_ipv6: Boolean = false
)

@Serializable
data class CreateServerResponse(
    val server: HetznerServer? = null
)

@Serializable
data class GetServerResponse(
    val server: HetznerServer? = null
)

@Serializable
data class HetznerServer(
    val id: Long? = null,
    val name: String? = null,
    val status: String? = null,
    val created: String? = null,
    val public_net: HetznerPublicNet? = null
)

@Serializable
data class HetznerPublicNet(
    val ipv4: HetznerIpv4? = null
)

@Serializable
data class HetznerIpv4(
    val id: Long? = null,
    val ip: String? = null,
    val blocked: Boolean? = null,
    val dns_ptr: String? = null
)

// ── Hetzner DNS models ───────────────────────────────────────────────────────

@Serializable
data class HetznerZonesResponse(
    val zones: List<HetznerZone>? = null
)

@Serializable
data class HetznerZone(
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class HetznerCreateDnsRecordRequest(
    val zone_id: String,
    val type: String,
    val name: String,
    val value: String,
    val ttl: Int
)

@Serializable
data class HetznerCreateDnsRecordResponse(
    val record: HetznerDnsRecord? = null
)

@Serializable
data class HetznerDnsRecordsResponse(
    val records: List<HetznerDnsRecord>? = null
)

@Serializable
data class HetznerDnsRecord(
    val id: String? = null,
    val zone_id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val value: String? = null,
    val ttl: Int? = null
)
