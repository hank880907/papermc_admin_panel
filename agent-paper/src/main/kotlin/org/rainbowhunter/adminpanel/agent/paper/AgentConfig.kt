package org.rainbowhunter.adminpanel.agent.paper

import org.yaml.snakeyaml.Yaml
import java.io.Reader
import java.time.Duration

data class AgentConfig(
    val coreUrl: String,
    val coreToken: String,
    val serverId: String,
    val displayName: String,
    val reconnectInitial: Duration,
    val reconnectMax: Duration,
    val heartbeatInterval: Duration,
) {
    companion object {
        fun parse(reader: Reader): AgentConfig {
            @Suppress("UNCHECKED_CAST")
            val root = (Yaml().load<Any?>(reader) as? Map<String, Any?>)
                ?: throw ConfigException("config root is not a YAML map")
            return fromMap(root)
        }

        fun fromMap(root: Map<String, Any?>): AgentConfig {
            val core = section(root, "core")
            val server = section(root, "server")
            val reconnect = optionalSection(root, "reconnect")
            val heartbeat = optionalSection(root, "heartbeat")

            return AgentConfig(
                coreUrl = requireString(core, "core.url").trimEnd('/'),
                coreToken = requireString(core, "core.token"),
                serverId = requireString(server, "server.id"),
                displayName = requireString(server, "server.displayName"),
                reconnectInitial = Duration.ofSeconds(seconds(reconnect, "reconnect.initialDelaySeconds", 1L)),
                reconnectMax = Duration.ofSeconds(seconds(reconnect, "reconnect.maxDelaySeconds", 60L)),
                heartbeatInterval = Duration.ofSeconds(seconds(heartbeat, "heartbeat.intervalSeconds", 10L)),
            )
        }

        private fun section(root: Map<String, Any?>, key: String): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return (root[key] as? Map<String, Any?>)
                ?: throw ConfigException("missing required section: $key")
        }

        private fun optionalSection(root: Map<String, Any?>, key: String): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return (root[key] as? Map<String, Any?>) ?: emptyMap()
        }

        private fun requireString(section: Map<String, Any?>, dottedKey: String): String {
            val leaf = dottedKey.substringAfterLast('.')
            val value = section[leaf]?.toString()?.trim().orEmpty()
            if (value.isEmpty()) throw ConfigException("missing or empty config value: $dottedKey")
            return value
        }

        private fun seconds(section: Map<String, Any?>, dottedKey: String, default: Long): Long {
            val leaf = dottedKey.substringAfterLast('.')
            val raw = section[leaf] ?: return default
            return (raw as? Number)?.toLong()
                ?: raw.toString().toLongOrNull()
                ?: throw ConfigException("invalid number for $dottedKey: $raw")
        }
    }
}

class ConfigException(message: String) : RuntimeException(message)
