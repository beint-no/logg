package no.beint.logg

import org.slf4j.spi.MDCAdapter
import java.util.ArrayDeque
import java.util.Deque

internal class LoggMdc : MDCAdapter {
    private val values = ThreadLocal<MutableMap<String, String?>>()
    private val stacks = ThreadLocal<MutableMap<String, Deque<String>>>()

    override fun put(key: String?, value: String?) {
        requireNotNull(key) { "MDC key is required" }
        val map = values.get() ?: mutableMapOf<String, String?>().also(values::set)
        map[key] = value
    }

    override fun get(key: String?): String? = values.get()?.get(key)
    override fun remove(key: String?) { values.get()?.remove(key) }
    override fun clear() { values.remove() }
    override fun getCopyOfContextMap(): MutableMap<String, String?>? = values.get()?.toMutableMap()
    override fun setContextMap(map: MutableMap<String, String?>?) {
        if (map == null) values.remove() else values.set(map.toMutableMap())
    }

    override fun pushByKey(key: String, value: String) {
        val map = stacks.get() ?: mutableMapOf<String, Deque<String>>().also(stacks::set)
        map.getOrPut(key) { ArrayDeque() }.push(value)
    }

    override fun popByKey(key: String): String? = stacks.get()?.get(key)?.poll()
    override fun getCopyOfDequeByKey(key: String): Deque<String>? = stacks.get()?.get(key)?.let { ArrayDeque(it) }
    override fun clearDequeByKey(key: String) { stacks.get()?.remove(key) }
}
