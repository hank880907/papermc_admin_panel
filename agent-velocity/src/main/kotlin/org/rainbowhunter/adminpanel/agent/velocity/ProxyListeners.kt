package org.rainbowhunter.adminpanel.agent.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import org.rainbowhunter.adminpanel.agent.common.AgentClient
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.Player as PlayerDto

class ProxyListeners(private val client: AgentClient) {
    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val p = event.player
        client.send(AgentEnvelope.PlayerJoin(PlayerDto(p.uniqueId.toString(), p.username)))
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val p = event.player
        client.send(AgentEnvelope.PlayerQuit(PlayerDto(p.uniqueId.toString(), p.username)))
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val p = event.player
        val from = event.previousServer.orElse(null)?.serverInfo?.name
        val to = event.server.serverInfo.name
        client.send(AgentEnvelope.ServerSwitch(PlayerDto(p.uniqueId.toString(), p.username), from, to))
    }
}
