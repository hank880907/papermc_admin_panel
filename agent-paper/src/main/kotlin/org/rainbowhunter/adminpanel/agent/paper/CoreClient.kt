package org.rainbowhunter.adminpanel.agent.paper

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class IssueRegisterRequest(val mcUuid: String, val username: String, val isOp: Boolean)

@Serializable
data class IssueRegisterResponse(val url: String)

@Serializable
data class GrantViaAgentRequest(val granterMcUuid: String, val mcUuid: String, val username: String)

@Serializable
data class GrantUserResponse(val userId: Int, val mcUuid: String, val username: String, val isAdmin: Boolean)

@Serializable
data class CoreErrorBody(val error: String)

sealed class CoreError(message: String) : Exception(message) {
    class Forbidden(val body: String) : CoreError("forbidden: $body")
    class Conflict(val body: String) : CoreError("conflict: $body")
    class Unexpected(val status: Int, val body: String) : CoreError("unexpected $status: $body")
}

open class CoreClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val token: String,
) {
    open suspend fun issueRegisterToken(mcUuid: String, username: String, isOp: Boolean): IssueRegisterResponse {
        val response = httpClient.post("$baseUrl/api/auth/register/issue") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(IssueRegisterRequest(mcUuid, username, isOp))
        }
        return response.requireOkBody()
    }

    open suspend fun grantUser(granterMcUuid: String, mcUuid: String, username: String): GrantUserResponse {
        val response = httpClient.post("$baseUrl/api/agent/grant") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(GrantViaAgentRequest(granterMcUuid, mcUuid, username))
        }
        return response.requireOkBody()
    }
}

private suspend inline fun <reified T> HttpResponse.requireOkBody(): T = when (status) {
    HttpStatusCode.OK -> body<T>()
    HttpStatusCode.Forbidden -> throw CoreError.Forbidden(bodyOrEmpty())
    HttpStatusCode.Conflict -> throw CoreError.Conflict(bodyOrEmpty())
    else -> throw CoreError.Unexpected(status.value, bodyOrEmpty())
}

private suspend fun HttpResponse.bodyOrEmpty(): String =
    runCatching { body<CoreErrorBody>().error }.getOrElse {
        runCatching { body<String>() }.getOrElse { "" }
    }
