package org.rainbowhunter.adminpanel.core.auth

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.rainbowhunter.adminpanel.core.audit.AuditLogRepository
import org.rainbowhunter.adminpanel.core.buildDataSource
import org.rainbowhunter.adminpanel.core.connectExposed
import org.rainbowhunter.adminpanel.core.runMigrations
import org.rainbowhunter.adminpanel.protocol.ProtocolJson
import java.nio.file.Files
import java.nio.file.Path

class AuthRoutingTest {
    private lateinit var ds: HikariDataSource
    private lateinit var dbPath: Path
    private lateinit var userRepo: UserRepository
    private lateinit var tokenRepo: RegistrationTokenRepository
    private lateinit var auditLog: AuditLogRepository
    private val argon2 = Argon2Hasher()
    private val agentToken = "test-agent-token"
    private val signingKey = "test-signing-key-32-bytes-of-noise"
    private val publicUrl = "http://localhost:8080"

    @BeforeEach
    fun setup() {
        dbPath = Files.createTempFile("admin-panel-auth-", ".db")
        Files.deleteIfExists(dbPath)
        ds = buildDataSource("jdbc:sqlite:$dbPath").also { it.maximumPoolSize = 1 }
        runMigrations(ds)
        connectExposed(ds)
        userRepo = UserRepository()
        tokenRepo = RegistrationTokenRepository()
        auditLog = AuditLogRepository()
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
            routing {
                authRoutes(userRepo, tokenRepo, auditLog, argon2, publicUrl)
                adminRoutes(userRepo, auditLog)
            }
        }
    }

    private fun ApplicationTestBuilder.testClient() = createClient {
        install(ClientContentNegotiation) { json(ProtocolJson) }
        install(HttpCookies)
    }

    @Test
    fun `bootstrap flow — first op registers, sets password, and logs in`() = testApplication {
        installTestApp()
        val client = testClient()

        val issueResp = client.post("/api/auth/register/issue") {
            header(HttpHeaders.Authorization, "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(IssueRegisterRequest("uuid-op-1", "OpPlayer", isOp = true))
        }
        assertEquals(HttpStatusCode.OK, issueResp.status)
        val token = issueResp.body<IssueRegisterResponse>().url.substringAfterLast("/")

        val validateResp = client.get("/api/auth/register/$token")
        assertEquals(HttpStatusCode.OK, validateResp.status)
        assertEquals("OpPlayer", validateResp.body<RegisterValidateResponse>().username)

        val completeResp = client.post("/api/auth/register/complete") {
            contentType(ContentType.Application.Json)
            setBody(RegisterCompleteRequest(token, "password123"))
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)

        val loginResp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("OpPlayer", "password123"))
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)
        val loginBody = loginResp.body<LoginResponse>()
        assertEquals("OpPlayer", loginBody.username)
        assertTrue(loginBody.isAdmin, "first bootstrap user should be admin")

        val statusResp = client.get("/api/auth/status")
        val statusBody = statusResp.body<StatusResponse>()
        assertTrue(statusBody.authenticated)
        assertEquals("OpPlayer", statusBody.username)
    }

    @Test
    fun `granted user flow — admin grants, user registers via agent, then logs in`() = testApplication {
        installTestApp()

        val adminUser = userRepo.grant("uuid-admin", "AdminUser", isAdmin = true)
        userRepo.setPasswordHash(adminUser.id, argon2.hash("adminpass"))

        val client = testClient()
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("AdminUser", "adminpass"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val grantResp = client.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(GrantUserRequest("uuid-bob", "Bob", isAdmin = false))
        }
        assertEquals(HttpStatusCode.OK, grantResp.status)

        val issueResp = client.post("/api/auth/register/issue") {
            header(HttpHeaders.Authorization, "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(IssueRegisterRequest("uuid-bob", "Bob", isOp = false))
        }
        assertEquals(HttpStatusCode.OK, issueResp.status)
        val token = issueResp.body<IssueRegisterResponse>().url.substringAfterLast("/")

        val completeResp = client.post("/api/auth/register/complete") {
            contentType(ContentType.Application.Json)
            setBody(RegisterCompleteRequest(token, "bobspassword"))
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)

        val bobClient = testClient()
        val bobLogin = bobClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("Bob", "bobspassword"))
        }
        assertEquals(HttpStatusCode.OK, bobLogin.status)
        val bobBody = bobLogin.body<LoginResponse>()
        assertEquals("Bob", bobBody.username)
        assertEquals(false, bobBody.isAdmin)
    }

    @Test
    fun `register issue without agent token returns 401`() = testApplication {
        installTestApp()
        val response = createClient {}.post("/api/auth/register/issue") {
            contentType(ContentType.Application.Json)
            setBody("""{"mcUuid":"x","username":"y","isOp":true}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `non-bootstrap unknown user is rejected from register issue`() = testApplication {
        installTestApp()
        userRepo.grant("uuid-existing", "Existing", isAdmin = true)
        val response = createClient {}.post("/api/auth/register/issue") {
            header(HttpHeaders.Authorization, "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody("""{"mcUuid":"uuid-stranger","username":"Stranger","isOp":true}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `logout without session returns 401`() = testApplication {
        installTestApp()
        val response = createClient {}.post("/api/auth/logout")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `logout with session clears it and writes an audit row`() = testApplication {
        installTestApp()
        val user = userRepo.grant("uuid-1", "Alice", isAdmin = false)
        userRepo.setPasswordHash(user.id, argon2.hash("password123"))
        val client = testClient()
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("Alice", "password123"))
        }
        assertEquals(HttpStatusCode.OK, login.status)

        val before = auditLog.count()
        val logoutResp = client.post("/api/auth/logout")
        assertEquals(HttpStatusCode.OK, logoutResp.status)
        assertTrue(auditLog.count() > before, "audit log should gain a logout row")

        val status = client.get("/api/auth/status").body<StatusResponse>()
        org.junit.jupiter.api.Assertions.assertEquals(false, status.authenticated, "session should be cleared")
    }

    @Test
    fun `login with wrong password returns 401`() = testApplication {
        installTestApp()
        val user = userRepo.grant("uuid-1", "Alice", isAdmin = false)
        userRepo.setPasswordHash(user.id, argon2.hash("rightpassword"))
        val response = testClient().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("Alice", "wrongpassword"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
