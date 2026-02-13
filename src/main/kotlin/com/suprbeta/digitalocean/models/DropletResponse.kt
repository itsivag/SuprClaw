package com.suprbeta.digitalocean.models

import kotlinx.serialization.Serializable

@Serializable
data class DropletResponse(
    val droplet: Droplet? = null,
    val links: Links? = null
)

@Serializable
data class Droplet(
    val id: Long? = null,
    val name: String,
    val memory: Int? = null,
    val vcpus: Int? = null,
    val disk: Int? = null,
    val locked: Boolean? = null,
    val status: String? = null,
    val created_at: String? = null,
    val features: List<String>? = null,
    val backup_ids: List<Long>? = null,
    val snapshot_ids: List<Long>? = null,
    val image: Image? = null,
    val size: Size? = null,
    val size_slug: String? = null,
    val networks: Networks? = null,
    val region: Region? = null,
    val tags: List<String>? = null,
    val vpc_uuid: String? = null
)

@Serializable
data class Image(
    val id: Long? = null,
    val name: String? = null,
    val distribution: String? = null,
    val slug: String? = null,
    val public: Boolean? = null,
    val regions: List<String>? = null,
    val created_at: String? = null,
    val min_disk_size: Int? = null,
    val type: String? = null,
    val size_gigabytes: Double? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val status: String? = null
)

@Serializable
data class Size(
    val slug: String? = null,
    val memory: Int? = null,
    val vcpus: Int? = null,
    val disk: Int? = null,
    val transfer: Double? = null,
    val price_monthly: Double? = null,
    val price_hourly: Double? = null,
    val regions: List<String>? = null,
    val available: Boolean? = null,
    val description: String? = null
)

@Serializable
data class Networks(
    val v4: List<NetworkV4>? = null,
    val v6: List<NetworkV6>? = null
)

@Serializable
data class NetworkV4(
    val ip_address: String? = null,
    val netmask: String? = null,
    val gateway: String? = null,
    val type: String? = null
)

@Serializable
data class NetworkV6(
    val ip_address: String? = null,
    val netmask: Int? = null,
    val gateway: String? = null,
    val type: String? = null
)

@Serializable
data class Region(
    val name: String? = null,
    val slug: String? = null,
    val features: List<String>? = null,
    val available: Boolean? = null,
    val sizes: List<String>? = null
)

@Serializable
data class Links(
    val actions: List<Action>? = null
)

@Serializable
data class Action(
    val id: Long? = null,
    val rel: String? = null,
    val href: String? = null
)
