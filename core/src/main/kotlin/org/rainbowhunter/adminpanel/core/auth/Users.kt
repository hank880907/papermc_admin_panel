package org.rainbowhunter.adminpanel.core.auth

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val mcUuid = varchar("mc_uuid", 36).uniqueIndex()
    val username = varchar("username", 64)
    val passwordHash = varchar("password_hash", 256).nullable()
    val isAdmin = bool("is_admin")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class UserRecord(
    val id: Int,
    val mcUuid: String,
    val username: String,
    val passwordHash: String?,
    val isAdmin: Boolean,
    val createdAt: Instant,
)

class UserRepository(
    private val clock: () -> Instant = Instant::now,
) {
    fun grant(mcUuid: String, username: String, isAdmin: Boolean = false): UserRecord = transaction {
        UsersTable.insert {
            it[UsersTable.mcUuid] = mcUuid
            it[UsersTable.username] = username
            it[UsersTable.passwordHash] = null
            it[UsersTable.isAdmin] = isAdmin
            it[UsersTable.createdAt] = clock()
        }
        UsersTable.selectAll().where { UsersTable.mcUuid eq mcUuid }.single().toUserRecord()
    }

    fun findById(id: Int): UserRecord? = transaction {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUserRecord()
    }

    fun findByMcUuid(mcUuid: String): UserRecord? = transaction {
        UsersTable.selectAll().where { UsersTable.mcUuid eq mcUuid }.singleOrNull()?.toUserRecord()
    }

    fun findByUsername(username: String): UserRecord? = transaction {
        UsersTable.selectAll().where { UsersTable.username eq username }.singleOrNull()?.toUserRecord()
    }

    fun setPasswordHash(id: Int, hash: String) {
        transaction {
            UsersTable.update({ UsersTable.id eq id }) {
                it[UsersTable.passwordHash] = hash
            }
        }
    }

    fun countAll(): Long = transaction { UsersTable.selectAll().count() }

    fun listAll(): List<UserRecord> = transaction {
        UsersTable.selectAll()
            .orderBy(UsersTable.createdAt)
            .map { it.toUserRecord() }
    }

    private fun ResultRow.toUserRecord() = UserRecord(
        id = this[UsersTable.id],
        mcUuid = this[UsersTable.mcUuid],
        username = this[UsersTable.username],
        passwordHash = this[UsersTable.passwordHash],
        isAdmin = this[UsersTable.isAdmin],
        createdAt = this[UsersTable.createdAt],
    )
}
