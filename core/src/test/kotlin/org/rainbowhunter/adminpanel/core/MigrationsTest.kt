package org.rainbowhunter.adminpanel.core

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MigrationsTest {
    @Test
    fun `flyway creates all expected tables on a fresh sqlite database`() {
        val tempDb = Files.createTempFile("admin-panel-test-", ".db")
        Files.deleteIfExists(tempDb)
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$tempDb"
            maximumPoolSize = 1
        })
        try {
            runMigrations(ds)
            val tables = ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                    buildSet {
                        while (rs.next()) add(rs.getString("name"))
                    }
                }
            }
            val expected = setOf("users", "agents", "registration_tokens", "audit_log")
            assertTrue(tables.containsAll(expected), "Missing tables. Got: $tables, expected: $expected")
        } finally {
            ds.close()
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun `users table has the columns declared in V1`() {
        val tempDb = Files.createTempFile("admin-panel-test-", ".db")
        Files.deleteIfExists(tempDb)
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$tempDb"
            maximumPoolSize = 1
        })
        try {
            runMigrations(ds)
            val columns = ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("PRAGMA table_info(users)")
                    buildSet {
                        while (rs.next()) add(rs.getString("name"))
                    }
                }
            }
            assertEquals(
                setOf("id", "mc_uuid", "username", "password_hash", "is_admin", "created_at"),
                columns,
            )
        } finally {
            ds.close()
            Files.deleteIfExists(tempDb)
        }
    }
}
