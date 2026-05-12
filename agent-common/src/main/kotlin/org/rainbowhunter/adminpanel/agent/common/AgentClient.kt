package org.rainbowhunter.adminpanel.agent.common

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.AgentType
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import org.slf4j.Logger

class AgentClient(
    private val config: AgentConfig,
    private val agentType: AgentType,
    private val httpClient: HttpClient,
    private val handle: suspend (CoreEnvelope, AgentClient) -> Unit,
    private val logger: Logger,
    private val json: Json = ProtocolJson,
) {
    @Volatile
    private var outbound: Channel<AgentEnvelope>? = null

    @Volatile
    private var connected: Boolean = false

    fun isConnected(): Boolean = connected

    fun send(envelope: AgentEnvelope) {
        outbound?.trySend(envelope)
    }

    fun start(scope: CoroutineScope) {
        scope.launch { runLoop(scope) }
    }

    private suspend fun runLoop(scope: CoroutineScope) {
        val backoff = ReconnectBackoff(config.reconnectInitial, config.reconnectMax)
        while (scope.isActive) {
            try {
                connectOnce(scope)
                backoff.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("agent: connection failed: ${e.message}")
            }
            val next = backoff.nextDelay()
            logger.info("agent: reconnecting in ${next.toMillis()} ms")
            delay(next.toMillis())
        }
    }

    private suspend fun connectOnce(scope: CoroutineScope) {
        val wsUrl = buildString {
            append(config.coreUrl.replace("https://", "wss://").replace("http://", "ws://"))
            append("/agent")
        }
        httpClient.webSocket(wsUrl, request = { header("Authorization", "Bearer ${config.coreToken}") }) {
            val channel = Channel<AgentEnvelope>(Channel.BUFFERED)
            outbound = channel
            connected = true
            logger.info("agent: connected to ${config.coreUrl}")

            send(Frame.Text(json.encodeToString<AgentEnvelope>(
                AgentEnvelope.Hello(config.serverId, agentType, config.displayName)
            )))

            val writer = scope.launch {
                for (env in channel) {
                    send(Frame.Text(json.encodeToString<AgentEnvelope>(env)))
                }
            }
            val heartbeat = scope.launch {
                while (isActive) {
                    delay(config.heartbeatInterval.toMillis())
                    channel.trySend(AgentEnvelope.Heartbeat)
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val env = try {
                        json.decodeFromString<CoreEnvelope>(text)
                    } catch (e: SerializationException) {
                        logger.warn("agent: cannot decode CoreEnvelope: ${e.message}")
                        continue
                    }
                    try {
                        handle(env, this@AgentClient)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("agent: handler threw for ${env::class.simpleName}: ${e.message}")
                    }
                }
            } finally {
                connected = false
                outbound = null
                channel.close()
                writer.cancel()
                heartbeat.cancel()
            }
        }
    }
}
