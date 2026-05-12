package org.rainbowhunter.adminpanel.agent.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.Player as PlayerDto

class PlayerListeners(private val client: AgentClient) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val p = event.player
        client.send(AgentEnvelope.PlayerJoin(PlayerDto(p.uniqueId.toString(), p.name)))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val p = event.player
        client.send(AgentEnvelope.PlayerQuit(PlayerDto(p.uniqueId.toString(), p.name)))
    }
}
