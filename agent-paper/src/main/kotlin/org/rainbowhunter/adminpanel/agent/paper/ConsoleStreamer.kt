package org.rainbowhunter.adminpanel.agent.paper

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.Layout
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout

class ConsoleStreamer(
    private val onLine: (timestamp: Long, level: String, message: String) -> Unit,
) : AbstractAppender(
    "AdminPanelConsoleStreamer",
    null as Filter?,
    PatternLayout.createDefaultLayout() as Layout<*>,
    true,
    Property.EMPTY_ARRAY,
) {
    override fun append(event: LogEvent) {
        try {
            onLine(event.timeMillis, event.level.toString(), event.message.formattedMessage)
        } catch (_: Exception) {
            // never let a downstream failure poison the logging pipeline
        }
    }

    fun attach() {
        val ctx = LogManager.getContext(false) as LoggerContext
        start()
        ctx.configuration.rootLogger.addAppender(this, null, null)
        ctx.updateLoggers()
    }

    fun detach() {
        val ctx = LogManager.getContext(false) as LoggerContext
        ctx.configuration.rootLogger.removeAppender(name)
        ctx.updateLoggers()
        stop()
    }
}
