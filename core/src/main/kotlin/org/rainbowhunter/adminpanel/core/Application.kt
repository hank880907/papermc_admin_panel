package org.rainbowhunter.adminpanel.core

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import java.io.File

data class CoreConfig(
    val dbPath: String,
    val agentRegistrationToken: String,
)

fun loadCoreConfig(config: ApplicationConfig): CoreConfig = CoreConfig(
    dbPath = config.property("adminpanel.db.path").getString(),
    agentRegistrationToken = config.property("adminpanel.agents.registrationToken").getString(),
)

@Suppress("unused")
fun Application.module() {
    val config = loadCoreConfig(environment.config)
    File(config.dbPath).absoluteFile.parentFile?.mkdirs()
    val dataSource = buildDataSource("jdbc:sqlite:${config.dbPath}")
    runMigrations(dataSource)
    connectExposed(dataSource)
    coreModule()
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
