package org.rainbowhunter.adminpanel.core.servers

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.rainbowhunter.adminpanel.core.agent.AgentRegistry
import org.rainbowhunter.adminpanel.core.agent.AgentRepository
import org.rainbowhunter.adminpanel.core.agent.agentModule
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import org.rainbowhunter.adminpanel.core.auth.Argon2Hasher
import org.rainbowhunter.adminpanel.core.auth.LoginRequest
import org.rainbowhunter.adminpanel.core.auth.RegistrationTokenRepository
import org.rainbowhunter.adminpanel.core.auth.UserRepository
import org.rainbowhunter.adminpanel.core.auth.authRoutes
import org.rainbowhunter.adminpanel.core.auth.installAuth
import org.rainbowhunter.adminpanel.core.auth.installSessions
import org.rainbowhunter.adminpanel.core.buildDataSource
import org.rainbowhunter.adminpanel.core.connectExposed
import org.rainbowhunter.adminpanel.core.runMigrations
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.AgentType
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ConsoleWebSocketTest {
    private lateinit var ds: HikariDataSource
    private lateinit var dbPath: Path
    private lateinit var userRepo: UserRepository
    private lateinit var tokenRepo: RegistrationTokenRepository
    private lateinit var auditLog: AuditLogRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var registry: AgentRegistry
    private val argon2 = Argon2Hasher()
    private val agentToken = "test-agent-token"
    private val signingKey = "test-signing-key-32-bytes-of-noise"

    @BeforeEach
    fun setup() {
        dbPath = Files.createTempFile("admin-panel-console-", ".db")
        Files.deleteIfExists(dbPath)
        ds = buildDataSource("jdbc:sqlite:$dbPath").also { it.maximumPoolSize = 1 }
        runMigrations(ds)
        connectExposed(ds)
        userRepo = UserRepository()
        tokenRepo = RegistrationTokenRepository()
        auditLog = AuditLogRepository()
        agentRepository = AgentRepository()
        registry = AgentRegistry()
    }

    @AfterEach
    fun teardown() {
        ds.close()
        Files.deleteIfExists(dbPath)
    }

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ServerContentNegotiation) { json(ProtocolJson) }
            installSessions(signingKey)
            installAuth(userRepo, agentToken)
            agentModule(registry, agentRepository, Duration.ofSeconds(5))
            routing {
                authRoutes(userRepo, tokenRepo, auditLog, argon2, "http://localhost:8080")
                serversRoutes(registry, auditLog, ProtocolJson, Duration.ofSeconds(2))
            }
        }
    }

    private fun ApplicationTestBuilder.browserClient() = createClient {
        install(ClientContentNegotiation) { json(ProtocolJson) }
        install(HttpCookies)
        install(ClientWebSockets)
    }

    private fun ApplicationTestBuilder.agentClient() = createClient { install(ClientWebSockets) }

    private suspend fun loginAsAdmin(client: io.ktor.client.HttpClient) {
        val admin = userRepo.grant("uuid-admin", "Admin", isAdmin = true)
        userRepo.setPasswordHash(admin.id, argon2.hash("adminpass"))
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("Admin", "adminpass"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `console subscriber receives ConsoleLine events and forwards typed commands as RunCommand`() = testApplication {
        installTestApp()
        val browser = browserClient()
        loginAsAdmin(browser)
        val agent = agentClient()
        val receivedRunCommand = CompletableDeferred<CoreEnvelope.RunCommand>()

        coroutineScope {
            val agentJob = launch {
                agent.webSocket("/agent", request = { header(HttpHeaders.Authorization, "Bearer $agentToken") }) {
                    send(ProtocolJson.encodeToString<AgentEnvelope>(
                        AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
                    ))
                    // Push a console line after a tiny delay so the browser subscriber is collecting first
                    launch {
                        delay(150)
                        send(ProtocolJson.encodeToString<AgentEnvelope>(
                            AgentEnvelope.ConsoleLine(
                                timestamp = 1_700_000_000_000L,
                                level = "INFO",
                                message = "Server started",
                            ),
                        ))
                    }
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val env = ProtocolJson.decodeFromString<CoreEnvelope>(frame.readText())
                        if (env is CoreEnvelope.RunCommand) {
                            receivedRunCommand.complete(env)
                            send(ProtocolJson.encodeToString<AgentEnvelope>(
                                AgentEnvelope.CommandResult(env.correlationId, true, "ok"),
                            ))
                        }
                    }
                }
            }
            withTimeout(2000) { while (!registry.isOnline("srv-1")) delay(20) }

            val received = CompletableDeferred<String>()
            val consoleJob = launch {
                browser.webSocket("/ws/console/srv-1") {
                    // First read: the streamed ConsoleLine
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        received.complete(frame.readText())
                        // Send a command
                        send("list")
                        break
                    }
                    // Keep socket open briefly while RunCommand round-trips
                    delay(300)
                }
            }

            val line = withTimeout(2000) { received.await() }
            assertTrue(line.contains("ConsoleLine"), "expected ConsoleLine in: $line")
            assertTrue(line.contains("Server started"), "expected console message in: $line")

            val cmd = withTimeout(2000) { receivedRunCommand.await() }
            assertEquals("list", cmd.command)

            consoleJob.cancelAndJoin()
            agentJob.cancelAndJoin()
        }
    }

    @Test
    fun `console WS without session is rejected`() = testApplication {
        installTestApp()
        val anonymous = createClient { install(ClientWebSockets) }
        val thrown = runCatching {
            anonymous.webSocket("/ws/console/srv-1") {
                // unreachable
            }
        }.exceptionOrNull()
        assertTrue(thrown != null, "expected WS upgrade to be rejected without a session")
    }
}
