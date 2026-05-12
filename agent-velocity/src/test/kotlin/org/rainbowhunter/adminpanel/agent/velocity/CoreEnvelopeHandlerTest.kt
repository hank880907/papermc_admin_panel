package org.rainbowhunter.adminpanel.agent.velocity

import com.velocitypowered.api.proxy.ProxyServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import org.rainbowhunter.adminpanel.protocol.Gamemode
import java.lang.reflect.Proxy

class CoreEnvelopeHandlerTest {

    private val handler = CoreEnvelopeHandler(unimplementedProxy())

    @ParameterizedTest(name = "{0} replies with unsupported CommandResult")
    @MethodSource("unsupportedEnvelopes")
    fun `non-proxy ops are reported as unsupported with the original correlation id`(envelope: CoreEnvelope) {
        val reply = handler.handle(envelope)
        require(reply is AgentEnvelope.CommandResult)
        assertEquals(correlationOf(envelope), reply.correlationId)
        assertFalse(reply.success)
        assertEquals("operation not supported on Velocity proxy", reply.output)
    }

    companion object {
        @JvmStatic
        fun unsupportedEnvelopes(): List<CoreEnvelope> = listOf(
            CoreEnvelope.RunCommand("c-1", "list"),
            CoreEnvelope.BanPlayer("c-2", "uuid-1", "bye"),
            CoreEnvelope.OpPlayer("c-3", "uuid-1", true),
            CoreEnvelope.SetGamemode("c-4", "uuid-1", Gamemode.CREATIVE),
            CoreEnvelope.Teleport("c-5", "uuid-1", "world", 0.0, 64.0, 0.0),
        )

        private fun unimplementedProxy(): ProxyServer = Proxy.newProxyInstance(
            ProxyServer::class.java.classLoader,
            arrayOf(ProxyServer::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException("test stub: ${method.name}") } as ProxyServer

        private fun correlationOf(envelope: CoreEnvelope): String = when (envelope) {
            is CoreEnvelope.RunCommand -> envelope.correlationId
            is CoreEnvelope.ListPlayers -> envelope.correlationId
            is CoreEnvelope.KickPlayer -> envelope.correlationId
            is CoreEnvelope.BanPlayer -> envelope.correlationId
            is CoreEnvelope.OpPlayer -> envelope.correlationId
            is CoreEnvelope.SetGamemode -> envelope.correlationId
            is CoreEnvelope.Teleport -> envelope.correlationId
            is CoreEnvelope.BroadcastMessage -> envelope.correlationId
        }
    }
}
