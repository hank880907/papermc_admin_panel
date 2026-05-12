package org.rainbowhunter.adminpanel.core

import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route

fun Route.staticWebRoute(basePackage: String = "web") {
    staticResources("/", basePackage)
}
