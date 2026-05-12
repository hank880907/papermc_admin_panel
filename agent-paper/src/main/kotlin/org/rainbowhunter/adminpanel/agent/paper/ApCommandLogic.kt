package org.rainbowhunter.adminpanel.agent.paper

sealed class ApRegisterResult {
    data class Issued(val url: String) : ApRegisterResult()
    data object NoAccess : ApRegisterResult()
    data class Error(val message: String) : ApRegisterResult()
}

sealed class ApGrantResult {
    data class Granted(val username: String) : ApGrantResult()
    data object NotAdmin : ApGrantResult()
    data object AlreadyGranted : ApGrantResult()
    data class Error(val message: String) : ApGrantResult()
}

object ApCommandLogic {
    suspend fun register(
        client: CoreClient,
        senderMcUuid: String,
        senderName: String,
        senderIsOp: Boolean,
    ): ApRegisterResult = try {
        val resp = client.issueRegisterToken(senderMcUuid, senderName, senderIsOp)
        ApRegisterResult.Issued(resp.url)
    } catch (e: CoreError.Forbidden) {
        ApRegisterResult.NoAccess
    } catch (e: Exception) {
        ApRegisterResult.Error(e.message ?: "unknown error")
    }

    suspend fun grant(
        client: CoreClient,
        granterMcUuid: String,
        targetMcUuid: String,
        targetUsername: String,
    ): ApGrantResult = try {
        val resp = client.grantUser(granterMcUuid, targetMcUuid, targetUsername)
        ApGrantResult.Granted(resp.username)
    } catch (e: CoreError.Forbidden) {
        ApGrantResult.NotAdmin
    } catch (e: CoreError.Conflict) {
        ApGrantResult.AlreadyGranted
    } catch (e: Exception) {
        ApGrantResult.Error(e.message ?: "unknown error")
    }
}
