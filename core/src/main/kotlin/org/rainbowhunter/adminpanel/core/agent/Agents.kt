package org.rainbowhunter.adminpanel.core.agent

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.rainbowhunter.adminpanel.protocol.AgentType
import java.time.Instant

object AgentsTable : Table("agents") {
    val id = integer("id").autoIncrement()
    val serverId = varchar("server_id", 128).uniqueIndex()
    val agentType = varchar("agent_type", 16)
    val displayName = varchar("display_name", 128)
    val lastSeenAt = timestamp("last_seen_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class AgentRepository {
    fun upsertOnHello(serverId: String, agentType: AgentType, displayName: String, lastSeen: Instant) {
        transaction {
            val exists = AgentsTable.selectAll().where { AgentsTable.serverId eq serverId }.any()
            if (exists) {
                AgentsTable.update({ AgentsTable.serverId eq serverId }) {
                    it[AgentsTable.displayName] = displayName
                    it[AgentsTable.lastSeenAt] = lastSeen
                }
            } else {
                AgentsTable.insert {
                    it[AgentsTable.serverId] = serverId
                    it[AgentsTable.agentType] = agentType.name.lowercase()
                    it[AgentsTable.displayName] = displayName
                    it[AgentsTable.lastSeenAt] = lastSeen
                }
            }
        }
    }

    fun touch(serverId: String, lastSeen: Instant) {
        transaction {
            AgentsTable.update({ AgentsTable.serverId eq serverId }) {
                it[AgentsTable.lastSeenAt] = lastSeen
            }
        }
    }
}
