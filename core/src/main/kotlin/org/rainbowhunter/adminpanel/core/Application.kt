package org.rainbowhunter.adminpanel.core

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.rainbowhunter.adminpanel.core.agent.AgentRegistry
import org.rainbowhunter.adminpanel.core.agent.AgentRepository
import org.rainbowhunter.adminpanel.core.agent.agentModule
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import org.rainbowhunter.adminpanel.core.auth.Argon2Hasher
import org.rainbowhunter.adminpanel.core.auth.RegistrationTokenRepository
import org.rainbowhunter.adminpanel.core.auth.UserRepository
import org.rainbowhunter.adminpanel.core.auth.adminRoutes
import org.rainbowhunter.adminpanel.core.auth.authRoutes
import org.rainbowhunter.adminpanel.core.auth.installAuth
import org.rainbowhunter.adminpanel.core.auth.installSessions
import org.rainbowhunter.adminpanel.core.servers.serversRoutes
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import java.io.File
import java.time.Duration

data class CoreConfig(
    val dbPath: String,
    val agentRegistrationToken: String,
    val heartbeatTimeout: Duration,
    val sessionSigningKey: String,
    val publicUrl: String,
)

fun loadCoreConfig(config: ApplicationConfig): CoreConfig = CoreConfig(
    dbPath = config.property("adminpanel.db.path").getString(),
    agentRegistrationToken = config.property("adminpanel.agents.registrationToken").getString(),
    heartbeatTimeout = Duration.ofSeconds(
        config.propertyOrNull("adminpanel.agents.heartbeatTimeoutSeconds")?.getString()?.toLong() ?: 30L,
    ),
    sessionSigningKey = config.property("adminpanel.session.signingKey").getString(),
    publicUrl = config.property("adminpanel.publicUrl").getString(),
)

@Suppress("unused")
fun Application.module() {
    val config = loadCoreConfig(environment.config)
    File(config.dbPath).absoluteFile.parentFile?.mkdirs()
    val dataSource = buildDataSource("jdbc:sqlite:${config.dbPath}")
    runMigrations(dataSource)
    connectExposed(dataSource)

    val registry = AgentRegistry()
    val agentRepository = AgentRepository()
    val userRepo = UserRepository()
    val tokenRepo = RegistrationTokenRepository()
    val auditLog = AuditLogRepository()
    val argon2 = Argon2Hasher()

    installSessions(config.sessionSigningKey)
    installAuth(userRepo, config.agentRegistrationToken)

    coreModule()
    agentModule(registry, agentRepository, config.heartbeatTimeout)

    routing {
        authRoutes(userRepo, tokenRepo, auditLog, argon2, config.publicUrl)
        adminRoutes(userRepo, auditLog)
        serversRoutes(registry, auditLog)
    }
}

fun Application.coreModule(staticBasePackage: String = "web") {
    install(ContentNegotiation) {
        json(ProtocolJson)
    }
    install(CallLogging)
    routing {
        healthRoute()
        staticWebRoute(staticBasePackage)
    }
}
