package org.rainbowhunter.adminpanel.core

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaticFilesTest {
    @Test
    fun `GET slash returns 200 with placeholder when index html is present`() = testApplication {
        application { coreModule(staticBasePackage = "test-web-present") }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("placeholder"))
    }

    @Test
    fun `GET slash returns 404 when index html is absent`() = testApplication {
        application { coreModule(staticBasePackage = "test-web-absent") }
        val response = client.get("/")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
