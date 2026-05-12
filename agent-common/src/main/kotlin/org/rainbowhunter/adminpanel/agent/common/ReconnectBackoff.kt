package org.rainbowhunter.adminpanel.agent.common

import java.time.Duration

class ReconnectBackoff(
    private val initial: Duration,
    private val max: Duration,
    private val multiplier: Int = 2,
) {
    private var attempt = 0

    fun nextDelay(): Duration {
        val raw = initial.toMillis() shl minOf(attempt, 30)
        val capped = minOf(raw, max.toMillis()).coerceAtLeast(0)
        attempt++
        return Duration.ofMillis(capped)
    }

    fun reset() {
        attempt = 0
    }

    companion object {
        fun schedule(initial: Duration, max: Duration, count: Int): List<Duration> {
            val b = ReconnectBackoff(initial, max)
            return List(count) { b.nextDelay() }
        }
    }
}
