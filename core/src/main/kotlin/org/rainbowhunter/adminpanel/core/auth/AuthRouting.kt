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
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import java.time.Duration

@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class LoginResponse(val username: String, val isAdmin: Boolean)
@Serializable data class StatusResponse(
    val authenticated: Boolean,
    val userCount: Long,
    val username: String? = null,
    val isAdmin: Boolean? = null,
)
@Serializable data class RegisterValidateResponse(val username: String)
@Serializable data class RegisterCompleteRequest(val token: String, val password: String)
@Serializable data class IssueRegisterRequest(val mcUuid: String, val username: String, val isOp: Boolean)
@Serializable data class IssueRegisterResponse(val url: String)

fun Route.authRoutes(
    userRepo: UserRepository,
    tokenRepo: RegistrationTokenRepository,
    auditLog: AuditLogRepository,
    argon2: Argon2Hasher,
    publicUrl: String,
    registrationTtl: Duration = Duration.ofHours(1),
) {
    route("/api/auth") {
        post("/login") {
            val req = call.receive<LoginRequest>()
            val user = userRepo.findByUsername(req.username)
            if (user?.passwordHash == null || !argon2.verify(user.passwordHash, req.password)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            call.sessions.set(UserSession(user.id))
            auditLog.write(user.id, "auth.login", "user:${user.id}", null)
            call.respond(LoginResponse(user.username, user.isAdmin))
        }

        authenticate(SESSION_AUTH_NAME) {
            post("/logout") {
                val principal = call.principal<UserPrincipal>()!!
                auditLog.write(principal.user.id, "auth.logout", "user:${principal.user.id}", null)
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.OK)
            }
        }

        get("/status") {
            val session = call.sessions.get<UserSession>()
            val user = session?.let { userRepo.findById(it.userId) }
            call.respond(StatusResponse(
                authenticated = user != null,
                userCount = userRepo.countAll(),
                username = user?.username,
                isAdmin = user?.isAdmin,
            ))
        }

        get("/register/{token}") {
            val token = call.parameters["token"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val userId = tokenRepo.validate(token)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val user = userRepo.findById(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(RegisterValidateResponse(user.username))
        }

        post("/register/complete") {
            val req = call.receive<RegisterCompleteRequest>()
            if (req.password.length < 8) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("password too short"))
                return@post
            }
            val userId = tokenRepo.validate(req.token)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid or expired token"))
            val hash = argon2.hash(req.password)
            userRepo.setPasswordHash(userId, hash)
            tokenRepo.consume(req.token)
            auditLog.write(userId, "auth.register.complete", "user:$userId", null)
            call.respond(HttpStatusCode.OK)
        }

        authenticate(AGENT_AUTH_NAME) {
            post("/register/issue") {
                val req = call.receive<IssueRegisterRequest>()
                var user = userRepo.findByMcUuid(req.mcUuid)
                if (user == null) {
                    val userCount = userRepo.countAll()
                    if (userCount == 0L && req.isOp) {
                        user = userRepo.grant(req.mcUuid, req.username, isAdmin = true)
                        auditLog.write(null, "auth.bootstrap", "user:${user.id}", null)
                    } else {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("no access"))
                        return@post
                    }
                }
                val token = tokenRepo.issue(user.id, registrationTtl)
                auditLog.write(null, "auth.register.issue", "user:${user.id}", null)
                call.respond(IssueRegisterResponse(url = "$publicUrl/register/$token"))
            }
        }
    }
}
