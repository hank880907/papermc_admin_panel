package org.rainbowhunter.adminpanel.agent.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.rainbowhunter.adminpanel.agent.common.AgentClient
import org.rainbowhunter.adminpanel.agent.common.AgentConfig
import org.rainbowhunter.adminpanel.agent.common.ConsoleStreamer
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.AgentType
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Plugin(id = "adminpanel", name = "Admin Panel", version = "0.1.0", description = "Admin Panel agent for Velocity")
class AdminPanelAgent @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) {
    private var scope: CoroutineScope? = null
    private var httpClient: HttpClient? = null
    private var streamer: ConsoleStreamer? = null

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val configPath = ensureConfig()
        val agentConfig = try {
            Files.newBufferedReader(configPath).use { AgentConfig.parse(it) }
        } catch (e: Exception) {
            logger.error("config error: ${e.message}; agent will not start")
            return
        }

        val handler = CoreEnvelopeHandler(proxy)

        val client = HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) { json(ProtocolJson) }
        }
        httpClient = client

        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = coroutineScope

        val agentClient = AgentClient(
            config = agentConfig,
            agentType = AgentType.VELOCITY,
            httpClient = client,
            handle = { envelope, ac ->
                val reply = handler.handle(envelope)
                ac.send(reply)
            },
            logger = logger,
        )
        agentClient.start(coroutineScope)

        val cs = ConsoleStreamer { ts, level, msg ->
            agentClient.send(AgentEnvelope.ConsoleLine(ts, level, msg))
        }
        cs.attach()
        streamer = cs

        proxy.eventManager.register(this, ProxyListeners(agentClient))

        logger.info("AdminPanel agent enabled; dialing ${agentConfig.coreUrl}")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        streamer?.detach()
        streamer = null
        scope?.cancel()
        scope = null
        httpClient?.close()
        httpClient = null
    }

    private fun ensureConfig(): Path {
        Files.createDirectories(dataDirectory)
        val configPath = dataDirectory.resolve("config.yml")
        if (Files.notExists(configPath)) {
            val resource = javaClass.classLoader.getResourceAsStream("config.yml")
                ?: throw IOException("default config.yml missing from plugin jar")
            resource.use { Files.copy(it, configPath, StandardCopyOption.REPLACE_EXISTING) }
        }
        return configPath
    }
}
