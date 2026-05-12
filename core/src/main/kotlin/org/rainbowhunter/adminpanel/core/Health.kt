package org.rainbowhunter.adminpanel.core

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun Route.healthRoute() {
    get("/api/health") {
        call.respond(HealthResponse(status = "ok"))
    }
}
