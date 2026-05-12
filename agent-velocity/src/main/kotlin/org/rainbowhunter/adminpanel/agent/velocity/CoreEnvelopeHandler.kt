package org.rainbowhunter.adminpanel.agent.velocity

import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.Player as PlayerDto
import java.util.UUID

class CoreEnvelopeHandler(private val proxy: ProxyServer) {
    fun handle(envelope: CoreEnvelope): AgentEnvelope = when (envelope) {
        is CoreEnvelope.KickPlayer -> result(envelope.correlationId) {
            val player = requireOnline(envelope.playerUuid)
            player.disconnect(Component.text(envelope.reason))
        }
        is CoreEnvelope.BroadcastMessage -> result(envelope.correlationId) {
            proxy.sendMessage(Component.text(envelope.message))
        }
        is CoreEnvelope.ListPlayers -> AgentEnvelope.PlayerListResult(
            envelope.correlationId,
            proxy.allPlayers.map { PlayerDto(it.uniqueId.toString(), it.username) },
        )
        is CoreEnvelope.RunCommand,
        is CoreEnvelope.BanPlayer,
        is CoreEnvelope.OpPlayer,
        is CoreEnvelope.SetGamemode,
        is CoreEnvelope.Teleport ->
            AgentEnvelope.CommandResult(envelope.correlationId(), false, "operation not supported on Velocity proxy")
    }

    private fun result(correlationId: String, block: () -> Unit): AgentEnvelope.CommandResult = try {
        block()
        AgentEnvelope.CommandResult(correlationId, true, "")
    } catch (e: Exception) {
        AgentEnvelope.CommandResult(correlationId, false, e.message ?: e::class.simpleName.orEmpty())
    }

    private fun requireOnline(uuid: String) =
        proxy.getPlayer(UUID.fromString(uuid)).orElseThrow {
            IllegalStateException("player not online: $uuid")
        }

    private fun CoreEnvelope.correlationId(): String = when (this) {
        is CoreEnvelope.RunCommand -> correlationId
        is CoreEnvelope.ListPlayers -> correlationId
        is CoreEnvelope.KickPlayer -> correlationId
        is CoreEnvelope.BanPlayer -> correlationId
        is CoreEnvelope.OpPlayer -> correlationId
        is CoreEnvelope.SetGamemode -> correlationId
        is CoreEnvelope.Teleport -> correlationId
        is CoreEnvelope.BroadcastMessage -> correlationId
    }
}
