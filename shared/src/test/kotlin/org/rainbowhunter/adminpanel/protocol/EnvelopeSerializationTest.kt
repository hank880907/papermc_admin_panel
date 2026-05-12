package org.rainbowhunter.adminpanel.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EnvelopeSerializationTest {

    @ParameterizedTest
    @MethodSource("agentEnvelopes")
    fun `agent envelope round-trips through JSON`(original: AgentEnvelope) {
        val json = ProtocolJson.encodeToString<AgentEnvelope>(original)
        val decoded = ProtocolJson.decodeFromString<AgentEnvelope>(json)
        assertEquals(original, decoded)
    }

    @ParameterizedTest
    @MethodSource("coreEnvelopes")
    fun `core envelope round-trips through JSON`(original: CoreEnvelope) {
        val json = ProtocolJson.encodeToString<CoreEnvelope>(original)
        val decoded = ProtocolJson.decodeFromString<CoreEnvelope>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `agent envelope emits type discriminator`() {
        val json = ProtocolJson.encodeToString<AgentEnvelope>(
            AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
        )
        assertTrue(json.contains("\"type\":\"Hello\""), "Expected discriminator in: $json")
    }

    @Test
    fun `core envelope emits type discriminator`() {
        val json = ProtocolJson.encodeToString<CoreEnvelope>(
            CoreEnvelope.RunCommand("c-1", "list"),
        )
        assertTrue(json.contains("\"type\":\"RunCommand\""), "Expected discriminator in: $json")
    }

    @Test
    fun `decoding agent envelope with unknown discriminator throws`() {
        assertThrows<SerializationException> {
            ProtocolJson.decodeFromString<AgentEnvelope>("""{"type":"NotARealMessage"}""")
        }
    }

    @Test
    fun `decoding agent envelope with missing discriminator throws`() {
        assertThrows<SerializationException> {
            ProtocolJson.decodeFromString<AgentEnvelope>("""{"serverId":"srv-1"}""")
        }
    }

    @Test
    fun `decoding core envelope with unknown discriminator throws`() {
        assertThrows<SerializationException> {
            ProtocolJson.decodeFromString<CoreEnvelope>("""{"type":"NotARealMessage"}""")
        }
    }

    companion object {
        @JvmStatic
        fun agentEnvelopes(): List<AgentEnvelope> = listOf(
            AgentEnvelope.Hello("srv-1", AgentType.PAPER, "Survival"),
            AgentEnvelope.Heartbeat,
            AgentEnvelope.ConsoleLine(
                timestamp = 1_700_000_000_000L,
                level = "INFO",
                message = "Server started",
            ),
            AgentEnvelope.PlayerJoin(Player("uuid-1", "Player1")),
            AgentEnvelope.PlayerQuit(Player("uuid-1", "Player1")),
            AgentEnvelope.CommandResult(
                correlationId = "c-1",
                success = true,
                output = "There are 0 of a max of 20 players online.",
            ),
        )

        @JvmStatic
        fun coreEnvelopes(): List<CoreEnvelope> = listOf(
            CoreEnvelope.RunCommand("c-1", "list"),
            CoreEnvelope.KickPlayer("c-2", "uuid-1", "Bye"),
            CoreEnvelope.BanPlayer("c-3", "uuid-1", "Banned"),
            CoreEnvelope.OpPlayer("c-4", "uuid-1", true),
            CoreEnvelope.SetGamemode("c-5", "uuid-1", Gamemode.CREATIVE),
            CoreEnvelope.Teleport("c-6", "uuid-1", "world", 0.0, 64.0, 0.0),
        )
    }
}
