package org.rainbowhunter.adminpanel.agent.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.time.Duration

class AgentConfigTest {

    @Test
    fun `happy path full YAML resolves all fields`() {
        val yaml = """
            core:
              url: 'https://core.example.com/'
              token: 'shared-token'
            server:
              id: 'survival-1'
              displayName: 'Survival'
            reconnect:
              initialDelaySeconds: 2
              maxDelaySeconds: 90
            heartbeat:
              intervalSeconds: 15
        """.trimIndent()
        val cfg = AgentConfig.parse(StringReader(yaml))
        assertEquals("https://core.example.com", cfg.coreUrl)
        assertEquals("shared-token", cfg.coreToken)
        assertEquals("survival-1", cfg.serverId)
        assertEquals("Survival", cfg.displayName)
        assertEquals(Duration.ofSeconds(2), cfg.reconnectInitial)
        assertEquals(Duration.ofSeconds(90), cfg.reconnectMax)
        assertEquals(Duration.ofSeconds(15), cfg.heartbeatInterval)
    }

    @Test
    fun `defaults are applied when reconnect and heartbeat sections are missing`() {
        val yaml = """
            core:
              url: 'http://localhost:8080'
              token: 'tk'
            server:
              id: 's'
              displayName: 'S'
        """.trimIndent()
        val cfg = AgentConfig.parse(StringReader(yaml))
        assertEquals(Duration.ofSeconds(1), cfg.reconnectInitial)
        assertEquals(Duration.ofSeconds(60), cfg.reconnectMax)
        assertEquals(Duration.ofSeconds(10), cfg.heartbeatInterval)
    }

    @Test
    fun `missing required core section throws`() {
        val yaml = """
            server:
              id: 's'
              displayName: 'S'
        """.trimIndent()
        assertThrows(ConfigException::class.java) { AgentConfig.parse(StringReader(yaml)) }
    }

    @Test
    fun `missing required server displayName throws`() {
        val yaml = """
            core:
              url: 'http://localhost:8080'
              token: 'tk'
            server:
              id: 'srv'
        """.trimIndent()
        assertThrows(ConfigException::class.java) { AgentConfig.parse(StringReader(yaml)) }
    }

    @Test
    fun `trailing slash is stripped from coreUrl`() {
        val yaml = """
            core:
              url: 'http://localhost:8080/'
              token: 'tk'
            server:
              id: 's'
              displayName: 'S'
        """.trimIndent()
        val cfg = AgentConfig.parse(StringReader(yaml))
        assertEquals("http://localhost:8080", cfg.coreUrl)
    }
}
