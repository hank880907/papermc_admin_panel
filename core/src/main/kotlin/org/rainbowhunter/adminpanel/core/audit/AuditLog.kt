package org.rainbowhunter.adminpanel.core.audit

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

object AuditLogTable : Table("audit_log") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").nullable()
    val action = varchar("action", 64)
    val target = varchar("target", 256).nullable()
    val payloadJson = text("payload_json").nullable()
    val at = timestamp("at")

    override val primaryKey = PrimaryKey(id)
}

class AuditLogRepository(
    private val clock: () -> Instant = Instant::now,
) {
    fun write(userId: Int?, action: String, target: String?, payload: String?) {
        transaction {
            AuditLogTable.insert {
                it[AuditLogTable.userId] = userId
                it[AuditLogTable.action] = action
                it[AuditLogTable.target] = target
                it[AuditLogTable.payloadJson] = payload
                it[AuditLogTable.at] = clock()
            }
        }
    }

    fun count(): Long = transaction { AuditLogTable.selectAll().count() }
}
