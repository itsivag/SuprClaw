package com.suprbeta.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdminMetricsParserTest {
    @Test
    fun `parse size units and percentages`() {
        assertEquals(2_621_440L, AdminMetricsParser.parseSizeToBytes("2.5MiB"))
        assertEquals(1_500_000L, AdminMetricsParser.parseSizeToBytes("1.5MB"))
        assertEquals(4_096L, AdminMetricsParser.parseSizeToBytes("4KiB"))
        assertEquals(123.45, AdminMetricsParser.parsePercent("123.45%"))
        assertNull(AdminMetricsParser.parseSizeToBytes("N/A"))
    }

    @Test
    fun `parse podman usage and io pairs`() {
        val (used, limit) = AdminMetricsParser.parseUsagePair("824KiB / 1GiB")
        val (rx, tx) = AdminMetricsParser.parseIoPair("12.5kB / 2.0MB")

        assertEquals(843_776L, used)
        assertEquals(1_073_741_824L, limit)
        assertEquals(12_500L, rx)
        assertEquals(2_000_000L, tx)
    }

    @Test
    fun `parse podman stats json line`() {
        val raw = """{"ID":"abc123","CPUPerc":"10.5%","MemUsage":"50MiB / 200MiB","NetIO":"10kB / 12kB"}"""
        val rows = AdminMetricsParser.parsePodmanStats(raw)

        assertEquals(1, rows.size)
        assertEquals("abc123", rows.first().containerId)
        assertEquals(10.5, rows.first().cpuPercent)
        assertEquals(52_428_800L, rows.first().memoryUsedBytes)
        assertEquals(209_715_200L, rows.first().memoryLimitBytes)
    }

    @Test
    fun `parse host snapshot output`() {
        val snapshot = AdminMetricsParser.parseHostSnapshot(
            """
            CPU_PERCENT=42.5
            MEM_TOTAL_BYTES=1000
            MEM_AVAILABLE_BYTES=250
            NET_RX_BYTES=300
            NET_TX_BYTES=400
            CORE_COUNT=4
            """.trimIndent()
        )

        assertNotNull(snapshot)
        assertEquals(42.5, snapshot.cpuPercent)
        assertEquals(750L, snapshot.memoryUsedBytes)
        assertEquals(1000L, snapshot.memoryTotalBytes)
        assertEquals(75.0, snapshot.memoryUsagePercent)
        assertEquals(4, snapshot.coreCount)
    }
}
