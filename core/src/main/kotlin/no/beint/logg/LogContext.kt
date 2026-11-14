package no.beint.logg

import org.slf4j.MDC

/** Immutable operation context; executor boundaries must capture and rebind explicitly. */
object LogContext {
    private val current: ScopedValue<Map<String, String>> = ScopedValue.newInstance()

    fun snapshot(): Map<String, String> = if (current.isBound) current.get() else emptyMap()

    fun run(fields: Map<String, String>, action: Runnable) {
        ScopedValue.where(current, java.util.Map.copyOf(snapshot() + fields)).run(action)
    }

    fun <T> call(fields: Map<String, String>, action: () -> T): T =
        ScopedValue.where(current, java.util.Map.copyOf(snapshot() + fields)).call<T, RuntimeException>(action)

    fun capture(action: Runnable): Runnable {
        val mdc: Map<String, String?> = MDC.getCopyOfContextMap().orEmpty()
        val captured = mdc.mapNotNull { (key, value) -> value?.let { key to it } }.toMap() + snapshot()
        val immutable = java.util.Map.copyOf(captured)
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            MDC.setContextMap(immutable)
            try {
                ScopedValue.where(current, immutable).run(action)
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }
}
