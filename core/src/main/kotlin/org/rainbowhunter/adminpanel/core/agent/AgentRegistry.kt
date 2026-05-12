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
    private val pending = ConcurrentHashMap<Pair<String, String>, CompletableDeferred<AgentEnvelope.CommandResult>>()

    private val _events = MutableSharedFlow<Pair<String, AgentEnvelope>>(extraBufferCapacity = 64)
    val events: SharedFlow<Pair<String, AgentEnvelope>> get() = _events.asSharedFlow()

    fun register(serverId: String, session: AgentSession): AgentConnection {
        val conn = AgentConnection(serverId, session, clock())
        connections[serverId] = conn
        return conn
    }

    fun unregister(connection: AgentConnection) {
        connections.remove(connection.serverId, connection)
    }

    fun isOnline(serverId: String): Boolean = connections.containsKey(serverId)

    fun onlineServerIds(): Set<String> = connections.keys.toSet()

    fun lastSeenAt(serverId: String): Instant? = connections[serverId]?.lastSeenAt

    suspend fun publish(serverId: String, envelope: AgentEnvelope) {
        connections[serverId]?.lastSeenAt = clock()
        if (envelope is AgentEnvelope.CommandResult) {
            pending.remove(serverId to envelope.correlationId)?.complete(envelope)
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
        val key = serverId to correlationId
        val deferred = CompletableDeferred<AgentEnvelope.CommandResult>()
        pending[key] = deferred
        try {
            session.send(command)
            return withTimeout(timeout.toMillis()) { deferred.await() }
        } finally {
            pending.remove(key)
        }
    }

    suspend fun prune(timeout: Duration) {
        val now = clock()
        val stale = connections.values.filter { Duration.between(it.lastSeenAt, now) > timeout }
        for (conn in stale) {
            if (connections.remove(conn.serverId, conn)) {
                try {
                    conn.session.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}
