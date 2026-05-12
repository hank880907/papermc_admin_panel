package org.rainbowhunter.adminpanel.core.auth

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

object RegistrationTokensTable : Table("registration_tokens") {
    val token = varchar("token", 128)
    val userId = integer("user_id")
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()

    override val primaryKey = PrimaryKey(token)
}

class RegistrationTokenRepository(
    private val clock: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
) {
    fun issue(userId: Int, ttl: Duration): String = transaction {
        val token = generateToken()
        RegistrationTokensTable.insert {
            it[RegistrationTokensTable.token] = token
            it[RegistrationTokensTable.userId] = userId
            it[RegistrationTokensTable.expiresAt] = clock().plus(ttl)
            it[RegistrationTokensTable.consumedAt] = null
        }
        token
    }

    fun validate(token: String): Int? = transaction {
        val row = RegistrationTokensTable.selectAll()
            .where { RegistrationTokensTable.token eq token }
            .singleOrNull() ?: return@transaction null
        if (row[RegistrationTokensTable.consumedAt] != null) return@transaction null
        if (row[RegistrationTokensTable.expiresAt].isBefore(clock())) return@transaction null
        row[RegistrationTokensTable.userId]
    }

    fun consume(token: String) {
        transaction {
            RegistrationTokensTable.update({ RegistrationTokensTable.token eq token }) {
                it[RegistrationTokensTable.consumedAt] = clock()
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
