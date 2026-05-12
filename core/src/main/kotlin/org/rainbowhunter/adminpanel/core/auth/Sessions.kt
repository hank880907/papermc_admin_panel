package org.rainbowhunter.adminpanel.core.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.session
import io.ktor.server.response.respond
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class UserSession(val userId: Int)

const val SESSION_COOKIE_NAME = "admin_panel_session"
const val SESSION_AUTH_NAME = "user-session"
const val AGENT_AUTH_NAME = "agent-bearer"

fun Application.installSessions(signingKey: String) {
    val keyBytes = MessageDigest.getInstance("SHA-256").digest(signingKey.toByteArray())
    install(Sessions) {
        cookie<UserSession>(SESSION_COOKIE_NAME) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 7L * 24 * 60 * 60
            transform(SessionTransportTransformerMessageAuthentication(keyBytes))
        }
    }
}

fun Application.installAuth(userRepo: UserRepository, agentToken: String) {
    install(Authentication) {
        session<UserSession>(SESSION_AUTH_NAME) {
            validate { session ->
                userRepo.findById(session.userId)?.let { UserPrincipal(it) }
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
        bearer(AGENT_AUTH_NAME) {
            authenticate { credential ->
                if (credential.token == agentToken) UserIdPrincipal("agent") else null
            }
        }
    }
}
