package org.rainbowhunter.adminpanel.core.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository

@Serializable data class GrantUserRequest(val mcUuid: String, val username: String, val isAdmin: Boolean = false)
@Serializable data class GrantUserResponse(
    val userId: Int,
    val mcUuid: String,
    val username: String,
    val isAdmin: Boolean,
)
@Serializable data class GrantViaAgentRequest(
    val granterMcUuid: String,
    val mcUuid: String,
    val username: String,
)
@Serializable data class UserListItem(
    val id: Int,
    val mcUuid: String,
    val username: String,
    val isAdmin: Boolean,
    val createdAt: Long,
)

fun Route.adminRoutes(
    userRepo: UserRepository,
    auditLog: AuditLogRepository,
) {
    authenticate(SESSION_AUTH_NAME) {
        route("/api/users") {
            get {
                val principal = call.principal<UserPrincipal>()!!
                if (!principal.user.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("admin required"))
                    return@get
                }
                val users = userRepo.listAll().map {
                    UserListItem(it.id, it.mcUuid, it.username, it.isAdmin, it.createdAt.toEpochMilli())
                }
                call.respond(users)
            }
            post {
                val principal = call.principal<UserPrincipal>()!!
                if (!principal.user.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("admin required"))
                    return@post
                }
                val req = call.receive<GrantUserRequest>()
                if (userRepo.findByMcUuid(req.mcUuid) != null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("already granted"))
                    return@post
                }
                val user = userRepo.grant(req.mcUuid, req.username, req.isAdmin)
                auditLog.write(principal.user.id, "user.grant", "user:${user.id}", null)
                call.respond(GrantUserResponse(user.id, user.mcUuid, user.username, user.isAdmin))
            }
        }
    }

    authenticate(AGENT_AUTH_NAME) {
        post("/api/agent/grant") {
            val req = call.receive<GrantViaAgentRequest>()
            val granter = userRepo.findByMcUuid(req.granterMcUuid)
            if (granter == null || !granter.isAdmin) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("granter not admin"))
                return@post
            }
            if (userRepo.findByMcUuid(req.mcUuid) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("already granted"))
                return@post
            }
            val user = userRepo.grant(req.mcUuid, req.username, isAdmin = false)
            auditLog.write(granter.id, "user.grant", "user:${user.id}", null)
            call.respond(GrantUserResponse(user.id, user.mcUuid, user.username, user.isAdmin))
        }
    }
}
