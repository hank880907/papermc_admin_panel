package org.rainbowhunter.adminpanel.core.auth

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.rainbowhunter.adminpanel.core.connectExposed
import org.rainbowhunter.adminpanel.core.runMigrations
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class RegistrationTokensTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var dbPath: Path
    private lateinit var userRepo: UserRepository
    private lateinit var tokenRepo: RegistrationTokenRepository
    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun setup() {
        dbPath = Files.createTempFile("admin-panel-regtok-", ".db")
        Files.deleteIfExists(dbPath)
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$dbPath"
            maximumPoolSize = 1
        })
        runMigrations(dataSource)
        connectExposed(dataSource)
        userRepo = UserRepository(clock = { now })
        tokenRepo = RegistrationTokenRepository(clock = { now })
    }

    @AfterEach
    fun teardown() {
        dataSource.close()
        Files.deleteIfExists(dbPath)
    }

    @Test
    fun `issued token validates to its user id`() {
        val user = userRepo.grant("uuid-1", "Alice", isAdmin = false)
        val token = tokenRepo.issue(user.id, Duration.ofHours(1))
        assertEquals(user.id, tokenRepo.validate(token))
    }

    @Test
    fun `expired token is rejected`() {
        val user = userRepo.grant("uuid-1", "Alice")
        val token = tokenRepo.issue(user.id, Duration.ofMinutes(10))
        now = now.plus(Duration.ofMinutes(11))
        assertNull(tokenRepo.validate(token))
    }

    @Test
    fun `consumed token is rejected`() {
        val user = userRepo.grant("uuid-1", "Alice")
        val token = tokenRepo.issue(user.id, Duration.ofHours(1))
        tokenRepo.consume(token)
        assertNull(tokenRepo.validate(token))
    }

    @Test
    fun `unknown token returns null`() {
        assertNull(tokenRepo.validate("nope-not-a-real-token"))
    }

    @Test
    fun `issued token is a non-trivial opaque string`() {
        val user = userRepo.grant("uuid-1", "Alice")
        val token = tokenRepo.issue(user.id, Duration.ofHours(1))
        assertNotNull(token)
        org.junit.jupiter.api.Assertions.assertTrue(token.length >= 32, "token should be reasonably long: $token")
    }
}
