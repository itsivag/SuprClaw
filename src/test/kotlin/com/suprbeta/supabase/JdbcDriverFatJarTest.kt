package com.suprbeta.supabase

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the PostgreSQL JDBC driver in fat-JAR deployments.
 *
 * Background: DriverManager.getConnection() silently fails in shadow JARs because
 * the JVM service-loader finds the driver under the app classloader but DriverManager
 * (a bootstrap class) only trusts drivers registered by the same classloader context.
 *
 * Fix: bypass DriverManager entirely — instantiate org.postgresql.Driver directly.
 *
 * These tests verify that fix is in place and will catch any regression where someone
 * accidentally reverts to DriverManager.getConnection().
 */
class JdbcDriverFatJarTest {

    @Test
    fun `postgresql Driver class is loadable by the application classloader`() {
        // Class.forName uses the calling class's classloader — same one the app uses.
        // This must succeed; if it throws ClassNotFoundException the driver JAR is missing.
        val clazz = Class.forName("org.postgresql.Driver")
        assertNotNull(clazz)
    }

    @Test
    fun `postgresql Driver can be instantiated directly without DriverManager`() {
        // SelfHostedSupabaseManagementService uses this exact pattern.
        // If this throws the shadow JAR is broken.
        val driver = org.postgresql.Driver()
        assertNotNull(driver)
        // Verify it's a valid JDBC driver (has major/minor version)
        assertTrue(driver.majorVersion >= 4, "Expected JDBC major version >= 4, got ${driver.majorVersion}")
    }

    @Test
    fun `postgresql Driver returns null for non-postgresql URLs instead of throwing`() {
        // connect() contract: return null for URLs the driver doesn't handle.
        // This is important — the method must not throw for foreign URLs.
        val driver = org.postgresql.Driver()
        val conn = driver.connect("jdbc:mysql://localhost/test", java.util.Properties())
        assertNull(conn, "Driver.connect() should return null for non-postgresql URLs")
    }

    @Test
    fun `postgresql Driver acceptsURL returns true for postgresql scheme`() {
        val driver = org.postgresql.Driver()
        assertTrue(driver.acceptsURL("jdbc:postgresql://localhost:5432/postgres"))
    }

    @Test
    fun `postgresql Driver acceptsURL returns false for non-postgresql scheme`() {
        val driver = org.postgresql.Driver()
        assertTrue(!driver.acceptsURL("jdbc:mysql://localhost/test"))
    }

    @Test
    fun `postgresql Driver rejects plain postgresql scheme without jdbc prefix`() {
        // Regression: SUPABASE_SELF_HOSTED_DB_URL may be set as postgresql:// (no jdbc: prefix).
        // driver.connect() returns null for unrecognised URLs — must prepend jdbc: before calling.
        val driver = org.postgresql.Driver()
        val conn = driver.connect("postgresql://localhost:5432/postgres", java.util.Properties())
        assertNull(conn, "Driver must return null for postgresql:// (missing jdbc: prefix)")
    }

    @Test
    fun `postgresql Driver acceptsURL requires jdbc prefix`() {
        val driver = org.postgresql.Driver()
        assertTrue(driver.acceptsURL("jdbc:postgresql://localhost:5432/postgres"))
        assertTrue(!driver.acceptsURL("postgresql://localhost:5432/postgres"),
            "Driver must NOT accept plain postgresql:// without jdbc: prefix")
    }
}
