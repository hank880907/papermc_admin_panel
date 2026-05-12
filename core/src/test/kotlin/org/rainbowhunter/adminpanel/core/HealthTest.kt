package org.rainbowhunter.adminpanel.core

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `GET api health returns 200 with status ok`() = testApplication {
        application { coreModule() }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
    }
}
