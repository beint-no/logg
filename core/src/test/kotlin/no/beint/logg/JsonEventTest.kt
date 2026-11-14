package no.beint.logg

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.event.Level
import tools.jackson.databind.json.JsonMapper
import java.util.stream.Stream
import kotlin.test.*

class JsonEventTest {
    companion object {
        @JvmStatic fun strings(): Stream<String> = ((0..127).map { it.toChar().toString() } +
            listOf("", "æøå", "中文", "😀", "\u2028\u2029", "\uD800", "\uDC00", "quotes\" slash\\ newline\n")).stream()
    }
    private val mapper = JsonMapper.builder().build()
    private fun encode(message: String? = "message", fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        mapper.readTree(JsonEvent.encode("test", Level.INFO, message, error, mapOf("tenantId" to "42"), fields, "test"))

    @ParameterizedTest @MethodSource("strings")
    fun roundTripsEveryControlCharacterAndUnicode(text: String) {
        val raw = JsonEvent.encode("test", Level.INFO, text, null, emptyMap(), mapOf(text to text), "test")
        assertEquals(1, raw.count { it == '\n' })
        val event = mapper.readTree(raw)
        val expected = if (text == "\uD800" || text == "\uDC00") "\uFFFD" else text
        assertEquals(expected, event["message"].asString())
        assertEquals(expected, event["fields"][expected].asString())
    }
    @Test fun scalarTypesArePreserved() {
        val fields = encode(fields = mapOf("int" to 7, "bool" to true, "null" to null, "float" to 1.5, "long" to Long.MAX_VALUE))["fields"]
        assertEquals(7, fields["int"].asInt()); assertTrue(fields["bool"].asBoolean())
        assertTrue(fields["null"].isNull); assertEquals(1.5, fields["float"].asDouble()); assertEquals(Long.MAX_VALUE, fields["long"].asLong())
    }
    @Test fun nanAndInfinityAreValidJson() {
        encode(fields = mapOf("nan" to Double.NaN, "positive" to Double.POSITIVE_INFINITY, "negative" to Float.NEGATIVE_INFINITY))
    }
    @Test fun eventIdsAreUniqueAndSequenceMonotonic() {
        val first = encode(); val second = encode()
        assertEquals(first["instance"].asString(), second["instance"].asString())
        assertTrue(second["seq"].asLong() > first["seq"].asLong())
    }
    @Test fun textAndContainersAreBounded() {
        val event = encode("x".repeat(30_000), mapOf("list" to (0..100), "map" to (0..100).associate { "$it" to it }))
        assertEquals(16_384 + "[truncated]".length, event["message"].asString().length)
        assertEquals(33, event["fields"]["list"].size())
        assertTrue(event["fields"]["map"]["_truncated"].asBoolean())
    }
    @Test fun noReflectiveDomainObjectSerialization() {
        val secret = object { override fun toString(): String = error("must never inspect") }
        encode(fields = mapOf("domain" to secret, "deep" to mapOf("a" to mapOf("b" to secret))))
    }
    @Test fun causeChainsAndStacksSurvive() {
        val event = encode(error = IllegalArgumentException("outer", IllegalStateException("root")))
        assertEquals("java.lang.IllegalArgumentException", event["error"]["type"].asString())
        assertEquals("java.lang.IllegalStateException", event["error"]["rootType"].asString())
        assertTrue(event["error"]["stack"].asString().contains("Caused by:"))
    }
    @Test fun cyclicCausesTerminate() {
        val first = IllegalStateException("first"); val second = IllegalArgumentException("second")
        first.initCause(second); second.initCause(first)
        val event = encode(error = first)
        assertTrue(event["error"]["stack"].asString().contains("CIRCULAR REFERENCE"))
    }
    @Test fun largeExceptionsAreExplicitlyTruncated() {
        val event = encode(error = IllegalStateException("x".repeat(100_000)))
        assertTrue(event["error"]["truncated"].asBoolean())
        assertTrue(event["error"]["stack"].asString().length <= 65_536)
    }
    @Test fun nullMessageIsValid() { assertTrue(encode(message = null)["message"].isNull) }
}
