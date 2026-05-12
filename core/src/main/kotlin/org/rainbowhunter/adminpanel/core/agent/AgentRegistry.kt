package org.rainbowhunter.adminpanel.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import org.rainbowhunter.adminpanel.protocol.AgentEnvelope
import org.rainbowhunter.adminpanel.protocol.CoreEnvelope
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface AgentSession {
    suspend fun send(envelope: CoreEnvelope)
    suspend fun close()
}

class AgentConnection internal constructor(
    val serverId: String,
    val session: AgentSession,
    @Volatile internal var lastSeenAt: Instant,
)

class AgentRegistry(
    private val clock: () -> Instant = Instant::now,
) {
    private val connections = ConcurrentHashMap<String, AgentConnection>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<AgentEnvelope.CommandResult>>()

    private val _events = MutableSharedFlow<Pair<String, AgentEnvelope>>(extraBufferCapacity = 64)
    val events: SharedFlow<Pair<String, AgentEnvelope>> get() = _events.asSharedFlow()

    fun register(serverId: String, session: AgentSession) {
        connections[serverId] = AgentConnection(serverId, session, clock())
    }

    fun unregister(serverId: String) {
        connections.remove(serverId)
    }

    fun isOnline(serverId: String): Boolean = connections.containsKey(serverId)

    fun onlineServerIds(): Set<String> = connections.keys.toSet()

    fun lastSeenAt(serverId: String): Instant? = connections[serverId]?.lastSeenAt

    suspend fun publish(serverId: String, envelope: AgentEnvelope) {
        connections[serverId]?.lastSeenAt = clock()
        if (envelope is AgentEnvelope.CommandResult) {
            pending.remove(envelope.correlationId)?.complete(envelope)
            return
        }
        _events.emit(serverId to envelope)
    }

    suspend fun dispatch(
        serverId: String,
        command: CoreEnvelope,
        correlationId: String,
        timeout: Duration,
    ): AgentEnvelope.CommandResult {
        val session = connections[serverId]?.session
            ?: throw IllegalStateException("agent $serverId not connected")
        val deferred = CompletableDeferred<AgentEnvelope.CommandResult>()
        pending[correlationId] = deferred
        try {
            session.send(command)
            return withTimeout(timeout.toMillis()) { deferred.await() }
        } finally {
            pending.remove(correlationId)
        }
    }

    suspend fun prune(timeout: Duration) {
        val now = clock()
        val stale = connections.values.filter { Duration.between(it.lastSeenAt, now) > timeout }
        for (conn in stale) {
            connections.remove(conn.serverId)
            try {
                conn.session.close()
            } catch (_: Exception) {
            }
        }
    }
}
