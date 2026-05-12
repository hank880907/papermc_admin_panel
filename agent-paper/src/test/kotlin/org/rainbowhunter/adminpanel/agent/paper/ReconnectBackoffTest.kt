package org.rainbowhunter.adminpanel.agent.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ReconnectBackoffTest {

    @Test
    fun `sequence doubles from initial up to max, then plateaus`() {
        val schedule = ReconnectBackoff.schedule(
            initial = Duration.ofMillis(100),
            max = Duration.ofMillis(800),
            count = 6,
        )
        assertEquals(
            listOf(
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofMillis(800),
                Duration.ofMillis(800),
                Duration.ofMillis(800),
            ),
            schedule,
        )
    }

    @Test
    fun `reset returns the schedule to initial`() {
        val b = ReconnectBackoff(Duration.ofMillis(50), Duration.ofMillis(400))
        repeat(4) { b.nextDelay() }
        b.reset()
        assertEquals(Duration.ofMillis(50), b.nextDelay())
        assertEquals(Duration.ofMillis(100), b.nextDelay())
    }
}
