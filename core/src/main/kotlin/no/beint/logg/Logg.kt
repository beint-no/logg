package no.beint.logg

import org.slf4j.event.Level

object Logg {
    @Volatile private var levels: Map<String, Int> = mapOf("" to Level.INFO.toInt())
    @Volatile internal var levelVersion = 0
        private set
    @Volatile private var sink: LogSink? = null
    @Volatile private var release = "bootstrap"

    internal fun threshold(logger: String): Int {
        val current = levels
        var prefix = logger
        while (true) {
            current[prefix]?.let { return it }
            val dot = prefix.lastIndexOf('.')
            if (dot < 0) return current[""] ?: Level.INFO.toInt()
            prefix = prefix.substring(0, dot)
        }
    }

    @Synchronized
    fun configure(config: LogConfig) {
        val replacement = LogSink(config)
        val previous = sink
        sink = replacement
        release = config.release
        previous?.close()
    }

    fun status(): LogStatus? = sink?.status()

    @Synchronized
    fun setLevel(name: String?, level: Int?) {
        val key = if (name == null || name.equals("ROOT", ignoreCase = true)) "" else name
        levels = levels.toMutableMap().apply { if (level == null) remove(key) else put(key, level) }.toMap()
        levelVersion++
    }

    internal fun emit(logger: String, level: Level, message: String?, error: Throwable?, fields: Map<String, Any?>) {
        val scoped = LogContext.snapshot()
        val mdc = LoggProvider.mdc.getCopyOfContextMap()
        val context = if (mdc.isNullOrEmpty()) scoped else mdc + scoped
        val line = try { JsonEvent.encode(logger, level, message, error, context, fields, release) }
            catch (encodingError: Exception) {
                JsonEvent.encode(logger, level, message, error, context,
                    mapOf("encodingFailure" to encodingError.javaClass.name), release)
            }
        val current = sink
        if (current == null) System.err.print(line) else current.submit(line)
    }

    @Synchronized
    fun shutdown() {
        sink?.close()
        sink = null
    }
}
