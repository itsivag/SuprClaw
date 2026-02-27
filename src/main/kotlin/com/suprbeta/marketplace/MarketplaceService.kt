package com.suprbeta.marketplace

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class MarketplaceService(
    private val httpClient: HttpClient,
    private val catalogUrl: String = System.getenv("MARKETPLACE_CATALOG_URL")
        ?: "https://raw.githubusercontent.com/itsivag/SuprClaw/main/marketplace/agents.json"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fetchMutex = Mutex()

    private var cachedCatalog: MarketplaceCatalog? = null
    private var cacheExpiresAt: Long = 0L
    private val cacheTtlMs = 5 * 60 * 1000L

    suspend fun getCatalog(): MarketplaceCatalog {
        cachedCatalog?.takeIf { System.currentTimeMillis() < cacheExpiresAt }?.let { return it }

        return fetchMutex.withLock {
            // Re-check inside lock in case another coroutine already fetched
            cachedCatalog?.takeIf { System.currentTimeMillis() < cacheExpiresAt }?.let { return it }

            val response = httpClient.get(catalogUrl) {
                accept(ContentType.Application.Json)
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to fetch marketplace catalog: ${response.status}")
            }

            val catalog = json.decodeFromString<MarketplaceCatalog>(response.body<String>())
            cachedCatalog = catalog
            cacheExpiresAt = System.currentTimeMillis() + cacheTtlMs
            catalog
        }
    }
}
