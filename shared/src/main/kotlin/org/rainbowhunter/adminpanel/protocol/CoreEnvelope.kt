package org.rainbowhunter.adminpanel.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class CoreEnvelope {
    @Serializable
    @SerialName("RunCommand")
    data class RunCommand(
        val correlationId: String,
        val command: String,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("ListPlayers")
    data class ListPlayers(
        val correlationId: String,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("KickPlayer")
    data class KickPlayer(
        val correlationId: String,
        val playerUuid: String,
        val reason: String,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("BanPlayer")
    data class BanPlayer(
        val correlationId: String,
        val playerUuid: String,
        val reason: String,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("OpPlayer")
    data class OpPlayer(
        val correlationId: String,
        val playerUuid: String,
        val op: Boolean,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("SetGamemode")
    data class SetGamemode(
        val correlationId: String,
        val playerUuid: String,
        val gamemode: Gamemode,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("Teleport")
    data class Teleport(
        val correlationId: String,
        val playerUuid: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
    ) : CoreEnvelope()

    @Serializable
    @SerialName("BroadcastMessage")
    data class BroadcastMessage(
        val correlationId: String,
        val message: String,
    ) : CoreEnvelope()
}
