package org.rainbowhunter.adminpanel.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AgentEnvelope {
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val serverId: String,
        val agentType: AgentType,
        val displayName: String,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("Heartbeat")
    data object Heartbeat : AgentEnvelope()

    @Serializable
    @SerialName("ConsoleLine")
    data class ConsoleLine(
        val timestamp: Long,
        val level: String,
        val message: String,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("PlayerJoin")
    data class PlayerJoin(
        val player: Player,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("PlayerQuit")
    data class PlayerQuit(
        val player: Player,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("ServerSwitch")
    data class ServerSwitch(
        val player: Player,
        val fromServer: String?,
        val toServer: String,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("CommandResult")
    data class CommandResult(
        val correlationId: String,
        val success: Boolean,
        val output: String,
    ) : AgentEnvelope()

    @Serializable
    @SerialName("PlayerListResult")
    data class PlayerListResult(
        val correlationId: String,
        val players: List<Player>,
    ) : AgentEnvelope()
}
