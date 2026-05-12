package org.rainbowhunter.adminpanel.core.agent

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.rainbowhunter.adminpanel.core.auth.UserRepository
import org.rainbowhunter.adminpanel.core.auth.installAuth
import org.rainbowhunter.adminpanel.core.auth.installSessions
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        val userRepo: UserRepository,
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
        return Env(AgentRegistry(), AgentRepository(), UserRepository(), ds, dbPath)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installAgentApp(env: Env, heartbeatTimeout: Duration = Duration.ofSeconds(5)) {
        application {
            install(ServerContentNegotiation) { json(ProtocolJson) }
            installSessions("test-signing-key-32-bytes-of-noise")
            installAuth(env.userRepo, "test-token")
            agentModule(env.registry, env.repository, heartbeatTimeout)
        }
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
                installAgentApp(env)
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
    fun `dispatch over a real WebSocket round-trips a CommandResult from the mock agent`() {
        val env = setupEnv()
        try {
            testApplication {
                installAgentApp(env)
                val client = createClient { install(ClientWebSockets) }
                coroutineScope {
                    val agentJob = launch {
                        client.webSocket("/agent", request = { header("Authorization", "Bearer test-token") }) {
                            send(ProtocolJson.encodeToString<AgentEnvelope>(
                                AgentEnvelope.Hello("srv-disp", AgentType.PAPER, "Survival"),
                            ))
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val cmd = ProtocolJson.decodeFromString<org.rainbowhunter.adminpanel.protocol.CoreEnvelope>(frame.readText())
                                if (cmd is org.rainbowhunter.adminpanel.protocol.CoreEnvelope.RunCommand) {
                                    send(ProtocolJson.encodeToString<AgentEnvelope>(
                                        AgentEnvelope.CommandResult(cmd.correlationId, true, "echoed: ${cmd.command}"),
                                    ))
                                    break
                                }
                            }
                        }
                    }
                    withTimeout(2000) {
                        while (!env.registry.isOnline("srv-disp")) delay(20)
                    }
                    val result = env.registry.dispatch(
                        serverId = "srv-disp",
                        command = org.rainbowhunter.adminpanel.protocol.CoreEnvelope.RunCommand("cid-99", "list"),
                        correlationId = "cid-99",
                        timeout = Duration.ofSeconds(2),
                    )
                    assertEquals("cid-99", result.correlationId)
                    assertEquals(true, result.success)
                    assertEquals("echoed: list", result.output)
                    agentJob.cancelAndJoin()
                }
            }
        } finally {
            env.teardown()
        }
    }

    @Test
    fun `WS upgrade with invalid token is rejected`() {
        val env = setupEnv()
        try {
            testApplication {
                installAgentApp(env)
                val client = createClient { install(ClientWebSockets) }
                val thrown = runCatching {
                    client.webSocket("/agent", request = { header("Authorization", "Bearer wrong-token") }) {
                        // unreachable
                    }
                }.exceptionOrNull()
                assertNotNull(thrown, "expected WS upgrade to fail with bad token")
            }
            val count = transaction { AgentsTable.selectAll().count() }
            assertEquals(0L, count)
        } finally {
            env.teardown()
        }
    }

    @Test
    fun `connection is removed from registry when heartbeat timeout is exceeded`() {
        val env = setupEnv()
        try {
            testApplication {
                installAgentApp(env, heartbeatTimeout = Duration.ofMillis(150))
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
