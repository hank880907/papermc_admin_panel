package org.rainbowhunter.adminpanel.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AgentType { PAPER, VELOCITY }

@Serializable
enum class Gamemode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }

@Serializable
data class Player(
    val uuid: String,
    val username: String,
)

@Serializable
data class AgentMeta(
    val serverId: String,
    val agentType: AgentType,
    val displayName: String,
)

@Serializable
data class ServerInfo(
    val serverId: String,
    val agentType: AgentType,
    val displayName: String,
    val online: Boolean,
    val lastSeenAt: Long? = null,
)

val ProtocolJson: Json = Json {
    encodeDefaults = true
}
