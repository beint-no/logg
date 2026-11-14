package no.beint.logg

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.event.LoggingEvent
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.LoggingEventAware

internal class LoggLogger(private val loggerName: String) : AbstractLogger(), LoggingEventAware {
    private data class Threshold(val version: Int, val level: Int)
    @Volatile private var threshold = Threshold(-1, Level.INFO.toInt())

    init { name = loggerName }

    private fun enabled(level: Level): Boolean {
        val version = Logg.levelVersion
        var cached = threshold
        if (cached.version != version) {
            cached = Threshold(version, Logg.threshold(loggerName))
            threshold = cached
        }
        return level.toInt() >= cached.level
    }

    override fun getFullyQualifiedCallerName(): String = LoggLogger::class.java.name
    override fun isTraceEnabled(): Boolean = enabled(Level.TRACE)
    override fun isDebugEnabled(): Boolean = enabled(Level.DEBUG)
    override fun isInfoEnabled(): Boolean = enabled(Level.INFO)
    override fun isWarnEnabled(): Boolean = enabled(Level.WARN)
    override fun isErrorEnabled(): Boolean = enabled(Level.ERROR)
    override fun isTraceEnabled(marker: Marker?): Boolean = isTraceEnabled
    override fun isDebugEnabled(marker: Marker?): Boolean = isDebugEnabled
    override fun isInfoEnabled(marker: Marker?): Boolean = isInfoEnabled
    override fun isWarnEnabled(marker: Marker?): Boolean = isWarnEnabled
    override fun isErrorEnabled(marker: Marker?): Boolean = isErrorEnabled

    override fun handleNormalizedLoggingCall(
        level: Level, marker: Marker?, message: String?, arguments: Array<out Any?>?, throwable: Throwable?,
    ) {
        val formatted = MessageFormatter.arrayFormat(message, arguments, throwable)
        Logg.emit(loggerName, level, formatted.message, throwable ?: formatted.throwable,
            if (marker == null) emptyMap() else mapOf("marker" to marker.name))
    }

    override fun log(event: LoggingEvent) {
        if (!enabled(event.level)) return
        val fields = linkedMapOf<String, Any?>()
        event.keyValuePairs?.forEach { fields[it.key] = it.value }
        event.markers?.takeIf { it.isNotEmpty() }?.let { markers -> fields["markers"] = markers.map { it.name } }
        val formatted = if (event.throwable == null) MessageFormatter.arrayFormat(event.message, event.argumentArray)
            else MessageFormatter.arrayFormat(event.message, event.argumentArray, event.throwable)
        Logg.emit(loggerName, event.level, formatted.message, event.throwable ?: formatted.throwable, fields)
    }
}
