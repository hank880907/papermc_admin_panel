package org.rainbowhunter.adminpanel.core.servers

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.rainbowhunter.adminpanel.core.agent.AgentRegistry
import org.rainbowhunter.adminpanel.core.agent.AgentsTable
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import org.rainbowhunter.adminpanel.core.auth.ErrorResponse
import org.rainbowhunter.adminpanel.core.auth.SESSION_AUTH_NAME
import org.rainbowhunter.adminpanel.core.auth.UserPrincipal
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.AgentType
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.Gamemode
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import org.rainbowhunter.adminpanel.protocol.ServerInfo
import java.time.Duration
import java.util.UUID

@Serializable data class KickRequest(val reason: String)
@Serializable data class BanRequest(val reason: String)
@Serializable data class OpRequest(val op: Boolean)
@Serializable data class GamemodeRequest(val gamemode: Gamemode)
@Serializable data class TeleportRequest(val world: String, val x: Double, val y: Double, val z: Double)
@Serializable data class BroadcastRequest(val message: String)

fun Route.serversRoutes(
    registry: AgentRegistry,
    auditLog: AuditLogRepository,
    json: Json = ProtocolJson,
    dispatchTimeout: Duration = Duration.ofSeconds(5),
) {
    authenticate(SESSION_AUTH_NAME) {
        get("/api/servers") {
            val infos = transaction {
                AgentsTable.selectAll().toList()
            }.map { row ->
                val serverId = row[AgentsTable.serverId]
                ServerInfo(
                    serverId = serverId,
                    agentType = AgentType.valueOf(row[AgentsTable.agentType].uppercase()),
                    displayName = row[AgentsTable.displayName],
                    online = registry.isOnline(serverId),
                    lastSeenAt = row[AgentsTable.lastSeenAt]?.toEpochMilli(),
                )
            }
            call.respond(infos)
        }

        get("/api/servers/{id}/players") {
            val serverId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing server id"))
            if (!registry.isOnline(serverId)) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("agent offline"))
                return@get
            }
            val cid = newCorrelationId()
            val players = try {
                registry.dispatchListPlayers(serverId, cid, dispatchTimeout).players
            } catch (e: Exception) {
                call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse(e.message ?: "dispatch failed"))
                return@get
            }
            call.respond(players)
        }

        post("/api/servers/{id}/broadcast") {
            val serverId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing server id"))
            val req = call.receive<BroadcastRequest>()
            if (req.message.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("message must not be blank"))
                return@post
            }
            val principal = call.principal<UserPrincipal>()!!
            if (!registry.isOnline(serverId)) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("agent offline"))
                return@post
            }
            val cid = newCorrelationId()
            val result = try {
                registry.dispatch(serverId, CoreEnvelope.BroadcastMessage(cid, req.message), cid, dispatchTimeout)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse(e.message ?: "dispatch failed"))
                return@post
            }
            auditLog.write(principal.user.id, "server.broadcast", "server:$serverId", null)
            call.respond(result)
        }

        route("/api/servers/{id}/players/{uuid}") {
            post("/kick") {
                val req = call.receive<KickRequest>()
                dispatchPlayerOp(call, registry, auditLog, dispatchTimeout, "player.kick") { cid, uuid ->
                    CoreEnvelope.KickPlayer(cid, uuid, req.reason)
                }
            }
            post("/ban") {
                val req = call.receive<BanRequest>()
                dispatchPlayerOp(call, registry, auditLog, dispatchTimeout, "player.ban") { cid, uuid ->
                    CoreEnvelope.BanPlayer(cid, uuid, req.reason)
                }
            }
            post("/op") {
                val req = call.receive<OpRequest>()
                dispatchPlayerOp(call, registry, auditLog, dispatchTimeout, "player.op") { cid, uuid ->
                    CoreEnvelope.OpPlayer(cid, uuid, req.op)
                }
            }
            post("/gamemode") {
                val req = call.receive<GamemodeRequest>()
                dispatchPlayerOp(call, registry, auditLog, dispatchTimeout, "player.gamemode") { cid, uuid ->
                    CoreEnvelope.SetGamemode(cid, uuid, req.gamemode)
                }
            }
            post("/teleport") {
                val req = call.receive<TeleportRequest>()
                dispatchPlayerOp(call, registry, auditLog, dispatchTimeout, "player.teleport") { cid, uuid ->
                    CoreEnvelope.Teleport(cid, uuid, req.world, req.x, req.y, req.z)
                }
            }
        }
    }

    authenticate(SESSION_AUTH_NAME) {
        webSocket("/ws/console/{serverId}") {
            val serverId = call.parameters["serverId"] ?: return@webSocket
            val forwarder = launch {
                registry.events
                    .filter { (sid, env) -> sid == serverId && env is AgentEnvelope.ConsoleLine }
                    .collect { (_, env) ->
                        send(json.encodeToString<AgentEnvelope>(env))
                    }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val command = frame.readText()
                    val cid = newCorrelationId()
                    runCatching {
                        registry.dispatch(
                            serverId = serverId,
                            command = CoreEnvelope.RunCommand(cid, command),
                            correlationId = cid,
                            timeout = dispatchTimeout,
                        )
                    }
                }
            } finally {
                forwarder.cancel()
            }
        }
    }
}

private suspend fun dispatchPlayerOp(
    call: ApplicationCall,
    registry: AgentRegistry,
    auditLog: AuditLogRepository,
    timeout: Duration,
    action: String,
    buildCommand: (correlationId: String, uuid: String) -> CoreEnvelope,
) {
    val serverId = call.parameters["id"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing server id"))
    val uuid = call.parameters["uuid"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing player uuid"))
    val principal = call.principal<UserPrincipal>()!!
    if (!registry.isOnline(serverId)) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("agent offline"))
        return
    }
    val cid = newCorrelationId()
    val command = buildCommand(cid, uuid)
    val result = try {
        registry.dispatch(serverId, command, cid, timeout)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse(e.message ?: "dispatch failed"))
        return
    }
    auditLog.write(principal.user.id, action, "server:$serverId/player:$uuid", null)
    call.respond(result)
}

private fun newCorrelationId(): String = UUID.randomUUID().toString()
