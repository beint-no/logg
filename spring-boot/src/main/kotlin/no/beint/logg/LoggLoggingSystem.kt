package no.beint.logg

import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.boot.logging.LogFile
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.logging.LoggingSystemFactory
import org.springframework.core.Ordered
import java.nio.file.Path
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.Level as JulLevel

class LoggLoggingSystem : LoggingSystem() {
    override fun beforeInitialize() = Unit
    override fun initialize(context: LoggingInitializationContext, location: String?, file: LogFile?) {
        require(location == null && file == null) { "Use logg.directory; XML and logging.file settings are not supported" }
        val env = requireNotNull(context.environment)
        val directory = env.getProperty("logg.directory")?.takeIf(String::isNotBlank)?.let(Path::of)
        Logg.configure(LogConfig(
            directory = directory,
            console = env.getProperty("logg.console", Boolean::class.java, directory == null),
            maxFileBytes = env.getProperty("logg.max-file-bytes", Long::class.java, 50L * 1024 * 1024),
            retentionDays = env.getProperty("logg.retention-days", Int::class.java, 30),
            archiveBytes = env.getProperty("logg.archive-bytes", Long::class.java, 1024L * 1024 * 1024),
            release = env.getProperty("logg.release", "unspecified"),
        ))
        Logger.getLogger("").apply { handlers.forEach(::removeHandler); addHandler(JulHandler()); level = JulLevel.INFO }
    }
    override fun setLogLevel(logger: String?, level: LogLevel?) {
        val root = logger == null || logger.equals("ROOT", true)
        Logger.getLogger(if (root) "" else logger).level = when (level) {
            null -> if (root) JulLevel.INFO else null
            LogLevel.OFF -> JulLevel.OFF
            LogLevel.TRACE -> JulLevel.FINEST
            LogLevel.DEBUG -> JulLevel.FINE
            LogLevel.INFO -> JulLevel.INFO
            LogLevel.WARN -> JulLevel.WARNING
            LogLevel.ERROR, LogLevel.FATAL -> JulLevel.SEVERE
        }
        Logg.setLevel(logger, when (level) {
            null -> null
            LogLevel.OFF -> Int.MAX_VALUE
            LogLevel.TRACE -> Level.TRACE.toInt()
            LogLevel.DEBUG -> Level.DEBUG.toInt()
            LogLevel.INFO -> Level.INFO.toInt()
            LogLevel.WARN -> Level.WARN.toInt()
            LogLevel.ERROR, LogLevel.FATAL -> Level.ERROR.toInt()
        })
    }
    override fun getShutdownHandler(): Runnable = Runnable(Logg::shutdown)
    class Factory : LoggingSystemFactory, Ordered {
        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
        override fun getLoggingSystem(loader: ClassLoader): LoggingSystem = LoggLoggingSystem()
    }
    private class JulHandler : Handler() {
        private val formatter = object : Formatter() { override fun format(record: LogRecord): String = formatMessage(record) }
        override fun publish(record: LogRecord?) {
            if (record == null || !isLoggable(record)) return
            val level = when {
                record.level.intValue() >= 1000 -> Level.ERROR
                record.level.intValue() >= 900 -> Level.WARN
                record.level.intValue() >= 800 -> Level.INFO
                record.level.intValue() >= 500 -> Level.DEBUG
                else -> Level.TRACE
            }
            val logger = LoggerFactory.getLogger(record.loggerName ?: "jul")
            if (logger.isEnabledForLevel(level)) logger.atLevel(level).setCause(record.thrown).log(formatter.format(record))
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
}
