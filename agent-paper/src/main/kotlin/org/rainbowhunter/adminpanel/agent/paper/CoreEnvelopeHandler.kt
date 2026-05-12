package org.rainbowhunter.adminpanel.agent.paper

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.Gamemode
import org.rainbowhunter.adminpanel.protocol.Player as PlayerDto
import java.util.UUID

class CoreEnvelopeHandler(private val main: MainThreadRunner) {
    fun handle(envelope: CoreEnvelope): AgentEnvelope = when (envelope) {
        is CoreEnvelope.RunCommand -> runCommand(envelope)
        is CoreEnvelope.ListPlayers -> listPlayers(envelope)
        is CoreEnvelope.KickPlayer -> result(envelope.correlationId) {
            @Suppress("DEPRECATION")
            requireOnline(envelope.playerUuid).kickPlayer(envelope.reason)
        }
        is CoreEnvelope.BanPlayer -> result(envelope.correlationId) {
            val uuid = UUID.fromString(envelope.playerUuid)
            @Suppress("DEPRECATION")
            Bukkit.getOfflinePlayer(uuid).banPlayer(envelope.reason)
            @Suppress("DEPRECATION")
            Bukkit.getPlayer(uuid)?.kickPlayer(envelope.reason)
        }
        is CoreEnvelope.OpPlayer -> result(envelope.correlationId) {
            Bukkit.getOfflinePlayer(UUID.fromString(envelope.playerUuid)).isOp = envelope.op
        }
        is CoreEnvelope.SetGamemode -> result(envelope.correlationId) {
            requireOnline(envelope.playerUuid).gameMode = when (envelope.gamemode) {
                Gamemode.SURVIVAL -> GameMode.SURVIVAL
                Gamemode.CREATIVE -> GameMode.CREATIVE
                Gamemode.ADVENTURE -> GameMode.ADVENTURE
                Gamemode.SPECTATOR -> GameMode.SPECTATOR
            }
        }
        is CoreEnvelope.Teleport -> result(envelope.correlationId) {
            val target = requireOnline(envelope.playerUuid)
            val world = Bukkit.getWorld(envelope.world) ?: error("unknown world: ${envelope.world}")
            target.teleport(org.bukkit.Location(world, envelope.x, envelope.y, envelope.z))
        }
    }

    private fun runCommand(env: CoreEnvelope.RunCommand): AgentEnvelope.CommandResult = try {
        val ok = main.run {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), env.command)
        }
        AgentEnvelope.CommandResult(env.correlationId, ok, if (ok) "" else "command not recognized")
    } catch (e: Exception) {
        AgentEnvelope.CommandResult(env.correlationId, false, e.message ?: e::class.simpleName.orEmpty())
    }

    private fun listPlayers(env: CoreEnvelope.ListPlayers): AgentEnvelope.PlayerListResult {
        val players = main.run {
            Bukkit.getOnlinePlayers().map { p -> PlayerDto(p.uniqueId.toString(), p.name) }
        }
        return AgentEnvelope.PlayerListResult(env.correlationId, players)
    }

    private fun result(correlationId: String, block: () -> Unit): AgentEnvelope.CommandResult {
        return try {
            main.run { block() }
            AgentEnvelope.CommandResult(correlationId, true, "")
        } catch (e: Exception) {
            AgentEnvelope.CommandResult(correlationId, false, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun requireOnline(uuid: String): Player {
        val parsed = UUID.fromString(uuid)
        return Bukkit.getPlayer(parsed) ?: error("player not online: $uuid")
    }
}
