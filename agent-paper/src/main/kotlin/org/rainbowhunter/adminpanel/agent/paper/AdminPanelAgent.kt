package org.rainbowhunter.adminpanel.agent.paper

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.ProtocolJson

class AdminPanelAgent : JavaPlugin() {
    private var scope: CoroutineScope? = null
    private var httpClient: HttpClient? = null
    private var streamer: ConsoleStreamer? = null

    override fun onEnable() {
        saveDefaultConfig()
        val configFile = java.io.File(dataFolder, "config.yml")
        val agentConfig = try {
            configFile.bufferedReader().use { AgentConfig.parse(it) }
        } catch (e: Exception) {
            slF4JLogger.error("config error: ${e.message}; disabling plugin")
            server.pluginManager.disablePlugin(this)
            return
        }

        val main = MainThreadRunner.bukkit(this)
        val handler = CoreEnvelopeHandler(main)

        val client = HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) { json(ProtocolJson) }
        }
        httpClient = client

        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = coroutineScope

        val agentClient = AgentClient(
            config = agentConfig,
            httpClient = client,
            handle = { envelope, ac ->
                val reply = handler.handle(envelope)
                ac.send(reply)
            },
            logger = slF4JLogger,
        )
        agentClient.start(coroutineScope)

        val cs = ConsoleStreamer { ts, level, msg ->
            agentClient.send(AgentEnvelope.ConsoleLine(ts, level, msg))
        }
        cs.attach()
        streamer = cs

        server.pluginManager.registerEvents(PlayerListeners(agentClient), this)

        val coreClient = CoreClient(client, agentConfig.coreUrl, agentConfig.coreToken)
        getCommand("ap")?.setExecutor(ApCommandExecutor(coreClient, coroutineScope, main))

        slF4JLogger.info("AdminPanel agent enabled; dialing ${agentConfig.coreUrl}")
    }

    override fun onDisable() {
        streamer?.detach()
        streamer = null
        scope?.cancel()
        scope = null
        httpClient?.close()
        httpClient = null
    }
}
