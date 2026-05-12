package org.rainbowhunter.adminpanel.agent.paper

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApCommandLogicTest {

    private class FakeCoreClient(
        private val onIssue: suspend () -> IssueRegisterResponse,
        private val onGrant: suspend () -> GrantUserResponse = { error("grant not stubbed") },
    ) : CoreClient(httpClient = io.ktor.client.HttpClient(), baseUrl = "", token = "") {
        override suspend fun issueRegisterToken(mcUuid: String, username: String, isOp: Boolean) = onIssue()
        override suspend fun grantUser(granterMcUuid: String, mcUuid: String, username: String) = onGrant()
    }

    @Test
    fun `register — success returns Issued with the URL Core supplied`() = runBlocking {
        val client = FakeCoreClient(onIssue = { IssueRegisterResponse("http://core/register/abc") })
        val result = ApCommandLogic.register(client, "uuid-1", "Alice", senderIsOp = false)
        assertEquals(ApRegisterResult.Issued("http://core/register/abc"), result)
    }

    @Test
    fun `register — 403 from Core maps to NoAccess`() = runBlocking {
        val client = FakeCoreClient(onIssue = { throw CoreError.Forbidden("no access") })
        val result = ApCommandLogic.register(client, "uuid-1", "Alice", senderIsOp = false)
        assertEquals(ApRegisterResult.NoAccess, result)
    }

    @Test
    fun `register — unexpected error returns Error with message`() = runBlocking {
        val client = FakeCoreClient(onIssue = { throw CoreError.Unexpected(500, "boom") })
        val result = ApCommandLogic.register(client, "uuid-1", "Alice", senderIsOp = true)
        assertTrue(result is ApRegisterResult.Error)
        assertTrue((result as ApRegisterResult.Error).message.contains("500"))
    }

    @Test
    fun `grant — success returns Granted with target username`() = runBlocking {
        val client = FakeCoreClient(
            onIssue = { error("not used") },
            onGrant = { GrantUserResponse(userId = 7, mcUuid = "uuid-bob", username = "Bob", isAdmin = false) },
        )
        val result = ApCommandLogic.grant(client, "uuid-admin", "uuid-bob", "Bob")
        assertEquals(ApGrantResult.Granted("Bob"), result)
    }

    @Test
    fun `grant — 403 from Core maps to NotAdmin`() = runBlocking {
        val client = FakeCoreClient(
            onIssue = { error("not used") },
            onGrant = { throw CoreError.Forbidden("granter not admin") },
        )
        val result = ApCommandLogic.grant(client, "uuid-non-admin", "uuid-bob", "Bob")
        assertEquals(ApGrantResult.NotAdmin, result)
    }

    @Test
    fun `grant — 409 from Core maps to AlreadyGranted`() = runBlocking {
        val client = FakeCoreClient(
            onIssue = { error("not used") },
            onGrant = { throw CoreError.Conflict("already granted") },
        )
        val result = ApCommandLogic.grant(client, "uuid-admin", "uuid-bob", "Bob")
        assertEquals(ApGrantResult.AlreadyGranted, result)
    }
}
