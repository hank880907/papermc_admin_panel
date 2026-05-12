package org.rainbowhunter.adminpanel.core.servers

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.rainbowhunter.adminpanel.core.agent.AgentRegistry
import org.rainbowhunter.adminpanel.core.agent.AgentRepository
import org.rainbowhunter.adminpanel.core.agent.AgentsTable
import org.rainbowhunter.adminpanel.core.agent.agentModule
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import org.rainbowhunter.adminpanel.core.audit.AuditLogTable
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
import org.rainbowhunter.adminpanel.protocol.ServerInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ServersRoutingTest {
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
        dbPath = Files.createTempFile("admin-panel-srv-", ".db")
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
    fun `GET api servers without session returns 401`() = testApplication {
        installTestApp()
        val response = createClient {}.get("/api/servers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api servers with session returns connected agent metadata`() = testApplication {
        installTestApp()
        val browser = browserClient()
        loginAsAdmin(browser)
        val agent = agentClient()
        coroutineScope {
            val agentJob = launch {
                agent.webSocket("/agent", request = { header(HttpHeaders.Authorization, "Bearer $agentToken") }) {
                    send(ProtocolJson.encodeToString<AgentEnvelope>(
                        AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
                    ))
                    for (frame in incoming) { /* keep alive */ }
                }
            }
            withTimeout(2000) { while (!registry.isOnline("srv-1")) delay(20) }
            val resp = browser.get("/api/servers")
            assertEquals(HttpStatusCode.OK, resp.status)
            val infos = resp.body<List<ServerInfo>>()
            val srv = infos.singleOrNull { it.serverId == "srv-1" }
            assertNotNull(srv, "expected srv-1 in /api/servers response")
            assertEquals(AgentType.PAPER, srv!!.agentType)
            assertEquals("Survival", srv.displayName)
            assertTrue(srv.online)
            agentJob.cancelAndJoin()
        }
    }

    @Test
    fun `GET players list dispatches ListPlayers to agent and returns the response`() = testApplication {
        installTestApp()
        val browser = browserClient()
        loginAsAdmin(browser)
        val agent = agentClient()
        var receivedList: CoreEnvelope.ListPlayers? = null
        coroutineScope {
            val agentJob = launch {
                agent.webSocket("/agent", request = { header(HttpHeaders.Authorization, "Bearer $agentToken") }) {
                    send(ProtocolJson.encodeToString<AgentEnvelope>(
                        AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
                    ))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val env = ProtocolJson.decodeFromString<CoreEnvelope>(frame.readText())
                        if (env is CoreEnvelope.ListPlayers) {
                            receivedList = env
                            send(ProtocolJson.encodeToString<AgentEnvelope>(
                                AgentEnvelope.PlayerListResult(
                                    env.correlationId,
                                    listOf(
                                        org.rainbowhunter.adminpanel.protocol.Player("uuid-1", "Alice"),
                                        org.rainbowhunter.adminpanel.protocol.Player("uuid-2", "Bob"),
                                    ),
                                ),
                            ))
                            break
                        }
                    }
                }
            }
            withTimeout(2000) { while (!registry.isOnline("srv-1")) delay(20) }
            val resp = browser.get("/api/servers/srv-1/players")
            assertEquals(HttpStatusCode.OK, resp.status)
            val players = resp.body<List<org.rainbowhunter.adminpanel.protocol.Player>>()
            assertEquals(2, players.size)
            assertEquals("Alice", players[0].username)
            assertEquals("Bob", players[1].username)
            assertNotNull(receivedList, "mock agent should have received a ListPlayers envelope")
            agentJob.cancelAndJoin()
        }
    }

    @Test
    fun `POST kick dispatches KickPlayer to agent and writes an audit row`() = testApplication {
        installTestApp()
        val browser = browserClient()
        loginAsAdmin(browser)
        val agent = agentClient()
        var receivedKick: CoreEnvelope.KickPlayer? = null
        coroutineScope {
            val agentJob = launch {
                agent.webSocket("/agent", request = { header(HttpHeaders.Authorization, "Bearer $agentToken") }) {
                    send(ProtocolJson.encodeToString<AgentEnvelope>(
                        AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
                    ))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val env = ProtocolJson.decodeFromString<CoreEnvelope>(frame.readText())
                        if (env is CoreEnvelope.KickPlayer) {
                            receivedKick = env
                            send(ProtocolJson.encodeToString<AgentEnvelope>(
                                AgentEnvelope.CommandResult(env.correlationId, true, "kicked"),
                            ))
                            break
                        }
                    }
                }
            }
            withTimeout(2000) { while (!registry.isOnline("srv-1")) delay(20) }
            val auditBefore = auditLog.count()
            val resp = browser.post("/api/servers/srv-1/players/uuid-victim/kick") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"griefing"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val auditAfter = auditLog.count()
            assertTrue(auditAfter > auditBefore, "audit log should gain at least one row")
            val kick = receivedKick
            assertNotNull(kick, "mock agent should have received a KickPlayer")
            assertEquals("uuid-victim", kick!!.playerUuid)
            assertEquals("griefing", kick.reason)
            agentJob.cancelAndJoin()
        }
        val auditAction = transaction {
            AuditLogTable.selectAll()
                .where { AuditLogTable.action eq "player.kick" }
                .singleOrNull()
        }
        assertNotNull(auditAction, "expected an audit_log row with action=player.kick")
        assertEquals("server:srv-1/player:uuid-victim", auditAction!![AuditLogTable.target])
    }
}
