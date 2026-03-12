package com.suprbeta.marketplace

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketplaceCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `marketplace catalog provisions cloud browser for bundled agents`() {
        val catalog = json.decodeFromString<MarketplaceCatalog>(
            Path.of("marketplace/agents.json").readText()
        )

        val seo = catalog.agents.find { it.id == "seo" }
        val content = catalog.agents.find { it.id == "content-writer" }

        assertNotNull(seo)
        assertNotNull(content)
        assertEquals(listOf("cloud_browser"), seo.mcpTools)
        assertEquals(listOf("cloud_browser"), content.mcpTools)
    }

    @Test
    fun `marketplace docs do not mention legacy firecrawl tools`() {
        val marketplaceRoot = Path.of("marketplace")
        val files = Files.walk(marketplaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter {
                    val name = it.fileName.toString()
                    name.endsWith(".md") || name == "agents.json"
                }
                .toList()
        }

        assertTrue(files.isNotEmpty(), "Expected marketplace docs to exist")

        files.forEach { path ->
            val content = path.readText().lowercase()
            assertFalse(
                "firecrawl" in content,
                "Legacy firecrawl reference found in $path"
            )
        }
    }
}
