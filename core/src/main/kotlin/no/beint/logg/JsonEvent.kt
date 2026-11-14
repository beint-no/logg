package no.beint.logg

import org.slf4j.event.Level
import java.io.PrintWriter
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.TemporalAccessor
import java.util.UUID

internal object JsonEvent {
    private val instance = UUID.randomUUID().toString()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private const val TEXT_LIMIT = 16_384
    private const val STACK_LIMIT = 65_536

    fun encode(
        logger: String, level: Level, message: String?, error: Throwable?,
        context: Map<String, String?>, fields: Map<String, Any?>, release: String,
    ): String = buildString(512) {
        append("""{"v":1,"ts":"""); text(Instant.now().toString())
        append(""","instance":"""); text(instance)
        append(""","seq":""").append(sequence.incrementAndGet())
        append(""","level":"""); text(level.name)
        append(""","logger":"""); text(logger)
        append(""","thread":"""); text(Thread.currentThread().name)
        append(""","release":"""); text(release)
        append(""","message":"""); text(message)
        append(""","context":"""); value(context, 0)
        append(""","fields":"""); value(fields, 0)
        if (error != null) {
            val stack = BoundedWriter()
            error.printStackTrace(PrintWriter(stack))
            append(""","error":{"type":"""); text(error.javaClass.name)
            append(""","message":"""); text(error.message)
            var root: Throwable = error
            repeat(16) { root.cause?.takeIf { it !== root }?.let { root = it } }
            append(""","rootType":"""); text(root.javaClass.name)
            append(""","rootMessage":"""); text(root.message)
            append(""","stack":"""); text(stack.text.toString(), STACK_LIMIT)
            append(""","truncated":""").append(stack.truncated).append('}')
        }
        append('}').append('\n')
    }

    private fun StringBuilder.value(value: Any?, depth: Int) {
        if (value == null) { append("null"); return }
        if (length > 65_536) { text("[event fields truncated]"); return }
        when {
            value is Boolean || value is Byte || value is Short || value is Int || value is Long ||
                value is BigInteger || value is BigDecimal -> append(value)
            value is Double && value.isFinite() -> append(value)
            value is Float && value.isFinite() -> append(value)
            value is Map<*, *> && depth < 2 -> {
                append('{')
                var count = 0
                for (entry in value.entries) {
                    if (count == 32) break
                    if (count++ > 0) append(',')
                    text(entry.key.toString()); append(':'); value(entry.value, depth + 1)
                }
                if (value.size > 32) append(""","_truncated":true""")
                append('}')
            }
            value is Iterable<*> && depth < 2 -> {
                append('[')
                val iterator = value.iterator()
                var count = 0
                while (iterator.hasNext() && count < 32) {
                    if (count++ > 0) append(',')
                    value(iterator.next(), depth + 1)
                }
                if (iterator.hasNext()) append(""", "[truncated]"""")
                append(']')
            }
            // Do not reflect on domain objects or dump their toString into operational logs.
            value is CharSequence || value is Char || value is Enum<*> || value is UUID || value is TemporalAccessor -> {
                try { text(value.toString()) } catch (_: RuntimeException) { text("[unprintable value]") }
            }
            else -> text("[${value.javaClass.simpleName}]")
        }
    }

    private fun StringBuilder.text(text: String?, limit: Int = TEXT_LIMIT) {
        if (text == null) { append("null"); return }
        append('"')
        for (index in 0 until minOf(text.length, limit)) {
            val original = text[index]
            val paired = when {
                original.isHighSurrogate() -> index + 1 < minOf(text.length, limit) && text[index + 1].isLowSurrogate()
                original.isLowSurrogate() -> index > 0 && text[index - 1].isHighSurrogate()
                else -> true
            }
            val char = if (paired) original else '\uFFFD'
            when (char) {
                '"', '\\' -> { append('\\'); append(char) }
                '\n' -> { append('\\'); append('n') }
                '\r' -> { append('\\'); append('r') }
                '\t' -> { append('\\'); append('t') }
                else -> if (char.code < 32 || char.isSurrogate()) {
                    append('\\').append('u')
                    for (shift in 12 downTo 0 step 4) append("0123456789abcdef"[(char.code shr shift) and 15])
                } else append(char)
            }
        }
        if (text.length > limit) append("[truncated]")
        append('"')
    }

    private class BoundedWriter : Writer() {
        val text = StringBuilder()
        var truncated = false
        override fun write(chars: CharArray, offset: Int, length: Int) {
            val remaining = STACK_LIMIT - text.length
            text.append(chars, offset, minOf(length, remaining))
            if (length > remaining) truncated = true
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
}
