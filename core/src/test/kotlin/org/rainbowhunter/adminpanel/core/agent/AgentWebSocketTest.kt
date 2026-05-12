package org.rainbowhunter.adminpanel.core.agent

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rainbowhunter.adminpanel.core.buildDataSource
import org.rainbowhunter.adminpanel.core.connectExposed
import org.rainbowhunter.adminpanel.core.runMigrations
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.AgentType
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AgentWebSocketTest {

    private data class Env(
        val registry: AgentRegistry,
        val repository: AgentRepository,
        val dataSource: HikariDataSource,
        val dbPath: Path,
    )

    private fun setupEnv(): Env {
        val dbPath = Files.createTempFile("admin-panel-ws-", ".db")
        Files.deleteIfExists(dbPath)
        val ds = buildDataSource("jdbc:sqlite:$dbPath").also {
            it.maximumPoolSize = 1
        }
        runMigrations(ds)
        connectExposed(ds)
        return Env(AgentRegistry(), AgentRepository(), ds, dbPath)
    }

    private fun Env.teardown() {
        dataSource.close()
        Files.deleteIfExists(dbPath)
    }

    @Test
    fun `valid token connects, Hello persists agent row and registers connection`() {
        val env = setupEnv()
        try {
            testApplication {
                application {
                    agentModule(env.registry, env.repository, "test-token", Duration.ofSeconds(5))
                }
                val client = createClient { install(ClientWebSockets) }
                client.webSocket("/agent", request = { header("Authorization", "Bearer test-token") }) {
                    val hello = AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival")
                    send(ProtocolJson.encodeToString<AgentEnvelope>(hello))
                    delay(200)
                }
            }
            val rowExists = transaction {
                AgentsTable.selectAll().where { AgentsTable.serverId eq "srv-1" }.singleOrNull()
            }
            assertNotNull(rowExists, "expected an agents row for srv-1")
            assertEquals("paper", rowExists!![AgentsTable.agentType])
            assertEquals("Survival", rowExists[AgentsTable.displayName])
            assertNotNull(rowExists[AgentsTable.lastSeenAt])
        } finally {
            env.teardown()
        }
    }

    @Test
    fun `invalid token returns 401 and no agent row is written`() {
        val env = setupEnv()
        try {
            testApplication {
                application {
                    agentModule(env.registry, env.repository, "test-token", Duration.ofSeconds(5))
                }
                val response = client.get("/agent") {
                    header("Authorization", "Bearer wrong-token")
                }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
            val count = transaction { AgentsTable.selectAll().count() }
            assertEquals(0L, count)
        } finally {
            env.teardown()
        }
    }

    @Test
    fun `missing token returns 401`() {
        val env = setupEnv()
        try {
            testApplication {
                application {
                    agentModule(env.registry, env.repository, "test-token", Duration.ofSeconds(5))
                }
                val response = client.get("/agent")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        } finally {
            env.teardown()
        }
    }

    @Test
    fun `connection is removed from registry when heartbeat timeout is exceeded`() {
        val env = setupEnv()
        try {
            testApplication {
                application {
                    agentModule(env.registry, env.repository, "test-token", Duration.ofMillis(150))
                }
                val client = createClient { install(ClientWebSockets) }
                client.webSocket("/agent", request = { header("Authorization", "Bearer test-token") }) {
                    val hello = AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival")
                    send(ProtocolJson.encodeToString<AgentEnvelope>(hello))
                    delay(100)
                    assertTrue(env.registry.isOnline("srv-1"), "agent should be online right after Hello")
                    delay(500)
                    assertEquals(false, env.registry.isOnline("srv-1"),
                        "agent should be pruned after exceeding heartbeat timeout")
                }
            }
        } finally {
            env.teardown()
        }
    }
}
