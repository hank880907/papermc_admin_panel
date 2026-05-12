package org.rainbowhunter.adminpanel.core.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import java.time.Duration
import java.time.Instant

private class CapturingSession : AgentSession {
    val sent = mutableListOf<CoreEnvelope>()
    var closed = false
        private set
    override suspend fun send(envelope: CoreEnvelope) { sent += envelope }
    override suspend fun close() { closed = true }
}

class AgentRegistryTest {
    @Test
    fun `dispatch resolves when matching CommandResult is published`() = runBlocking {
        val registry = AgentRegistry()
        val session = CapturingSession()
        registry.register("srv-1", session)

        val pending = async {
            registry.dispatch(
                serverId = "srv-1",
                command = CoreEnvelope.RunCommand("cid-1", "list"),
                correlationId = "cid-1",
                timeout = Duration.ofSeconds(1),
            )
        }
        yield()
        registry.publish("srv-1", AgentEnvelope.CommandResult("cid-1", true, "0 players"))
        val result = pending.await()

        assertEquals("cid-1", result.correlationId)
        assertTrue(result.success)
        assertEquals(1, session.sent.size)
        assertEquals(CoreEnvelope.RunCommand("cid-1", "list"), session.sent.single())
    }

    @Test
    fun `dispatch times out when no reply arrives within the deadline`() {
        val registry = AgentRegistry()
        registry.register("srv-1", CapturingSession())
        assertThrows<TimeoutCancellationException> {
            runBlocking {
                registry.dispatch(
                    serverId = "srv-1",
                    command = CoreEnvelope.RunCommand("cid-1", "list"),
                    correlationId = "cid-1",
                    timeout = Duration.ofMillis(50),
                )
            }
        }
    }

    @Test
    fun `dispatch fails fast when the target agent is not connected`() {
        val registry = AgentRegistry()
        assertThrows<IllegalStateException> {
            runBlocking {
                registry.dispatch(
                    serverId = "unknown",
                    command = CoreEnvelope.RunCommand("cid-1", "list"),
                    correlationId = "cid-1",
                    timeout = Duration.ofMillis(50),
                )
            }
        }
    }

    @Test
    fun `prune removes connections older than the timeout and closes their sessions`() = runBlocking {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val registry = AgentRegistry(clock = { now })
        val session = CapturingSession()
        registry.register("srv-1", session)
        assertTrue(registry.isOnline("srv-1"))

        now = now.plusSeconds(60)
        registry.prune(Duration.ofSeconds(30))

        assertFalse(registry.isOnline("srv-1"))
        assertTrue(session.closed)
    }

    @Test
    fun `unregister of a stale connection does not remove a newer registration for the same serverId`() {
        val registry = AgentRegistry()
        val oldConn = registry.register("srv-1", CapturingSession())
        val newConn = registry.register("srv-1", CapturingSession())
        assertTrue(registry.isOnline("srv-1"))

        registry.unregister(oldConn)

        assertTrue(registry.isOnline("srv-1"), "newer connection must survive stale unregister")
        assertTrue(oldConn !== newConn)
    }

    @Test
    fun `dispatch is isolated per agent — same correlationId from a different agent does not complete it`() = runBlocking {
        val registry = AgentRegistry()
        registry.register("srv-A", CapturingSession())
        registry.register("srv-B", CapturingSession())

        val pendingA = async {
            registry.dispatch(
                serverId = "srv-A",
                command = CoreEnvelope.RunCommand("dup", "from A"),
                correlationId = "dup",
                timeout = Duration.ofSeconds(2),
            )
        }
        yield()
        registry.publish("srv-B", AgentEnvelope.CommandResult("dup", true, "from B"))
        yield()
        assertFalse(pendingA.isCompleted, "A's dispatch must not be completed by B's reply")

        registry.publish("srv-A", AgentEnvelope.CommandResult("dup", true, "from A's agent"))
        val result = pendingA.await()
        assertEquals("from A's agent", result.output)
    }

    @Test
    fun `publish updates lastSeenAt on the registered connection`() = runBlocking {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val registry = AgentRegistry(clock = { now })
        registry.register("srv-1", CapturingSession())
        val first = registry.lastSeenAt("srv-1")

        now = now.plusSeconds(5)
        registry.publish("srv-1", AgentEnvelope.Heartbeat)
        val second = registry.lastSeenAt("srv-1")

        assertTrue(second!!.isAfter(first), "expected $second to be after $first")
    }
}
