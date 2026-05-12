package org.rainbowhunter.adminpanel.core.agent

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("AgentRouting")

class WsAgentSession(
    private val session: DefaultWebSocketSession,
    private val json: Json,
) : AgentSession {
    override suspend fun send(envelope: CoreEnvelope) {
        session.send(json.encodeToString<CoreEnvelope>(envelope))
    }

    override suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "heartbeat timeout"))
    }
}

fun Application.agentModule(
    registry: AgentRegistry,
    repository: AgentRepository,
    token: String,
    heartbeatTimeout: Duration,
    json: Json = ProtocolJson,
) {
    install(WebSockets)

    val pruneInterval = heartbeatTimeout.dividedBy(2).coerceAtLeast(Duration.ofMillis(50))
    launch {
        while (isActive) {
            delay(pruneInterval.toMillis())
            registry.prune(heartbeatTimeout)
        }
    }

    routing {
        route("/agent") {
            intercept(ApplicationCallPipeline.Plugins) {
                val provided = context.request.headers["Authorization"]
                    ?.removePrefix("Bearer ")?.trim()
                if (provided != token) {
                    context.respond(HttpStatusCode.Unauthorized)
                    finish()
                }
            }
            webSocket {
                handleAgentSession(registry, repository, json)
            }
        }
    }
}

private suspend fun DefaultWebSocketSession.handleAgentSession(
    registry: AgentRegistry,
    repository: AgentRepository,
    json: Json,
) {
    var conn: AgentConnection? = null
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val envelope = try {
                json.decodeFromString<AgentEnvelope>(text)
            } catch (e: SerializationException) {
                log.warn("Invalid envelope from ${conn?.serverId ?: "<pre-Hello>"}: ${e.message}")
                continue
            }

            when (envelope) {
                is AgentEnvelope.Hello -> {
                    val now = Instant.now()
                    repository.upsertOnHello(envelope.serverId, envelope.agentType, envelope.displayName, now)
                    conn = registry.register(envelope.serverId, WsAgentSession(this, json))
                }
                else -> {
                    val current = conn ?: continue
                    repository.touch(current.serverId, Instant.now())
                    registry.publish(current.serverId, envelope)
                }
            }
        }
    } finally {
        conn?.let { registry.unregister(it) }
    }
}
