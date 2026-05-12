package org.rainbowhunter.adminpanel.core.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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

fun Route.adminRoutes(
    userRepo: UserRepository,
    auditLog: AuditLogRepository,
) {
    authenticate(SESSION_AUTH_NAME) {
        route("/api/users") {
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
}
