package no.beint.logg

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.MarkerFactory
import org.slf4j.event.Level
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class ContextAndFacadeTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun reset() { Logg.shutdown(); MDC.clear(); Logg.setLevel("", null); Logg.setLevel("test", null); Logg.setLevel("test.child", null) }
    private fun rows() = Files.list(directory).use { files -> files.filter { it.toString().endsWith(".active.jsonl") }.flatMap(Files::lines).map { JsonMapper.builder().build().readTree(it) }.toList() }

    @ParameterizedTest @ValueSource(strings = ["plain", "marker", "fluent", "formatted", "trailing", "null"])
    fun exceptionsSurviveEverySlf4jEntryPoint(kind: String) {
        Logg.configure(LogConfig(directory))
        val logger = LoggerFactory.getLogger("test")
        val cause = IllegalStateException("cause")
        when (kind) {
            "plain" -> logger.error("plain", cause)
            "marker" -> logger.error(MarkerFactory.getMarker("TEST"), "marker", cause)
            "fluent" -> logger.atError().setCause(cause).log("fluent")
            "formatted" -> logger.error("format {}", "argument", cause)
            "trailing" -> logger.atError().log("format {}", "argument", cause)
            "null" -> logger.error(null, cause)
        }
        Logg.shutdown()
        assertEquals("java.lang.IllegalStateException", rows().single()["error"]["type"].asString())
    }
    @Test fun dynamicLevelsInheritAtPackageBoundaries() {
        val logger = LoggerFactory.getLogger("test.child")
        Logg.setLevel("test", Level.ERROR.toInt()); assertFalse(logger.isInfoEnabled)
        assertTrue(LoggerFactory.getLogger("test2").isInfoEnabled)
        Logg.setLevel("test.child", Level.DEBUG.toInt()); assertTrue(logger.isDebugEnabled)
        Logg.setLevel("test.child", null); assertFalse(logger.isDebugEnabled)
        Logg.setLevel("test", Int.MAX_VALUE); assertFalse(logger.isErrorEnabled)
    }
    @Test fun disabledSupplierIsNeverEvaluated() { LoggerFactory.getLogger("test").atDebug().addArgument { error("must not run") }.log("{}") }
    @Test fun scopedContextRestoresOnReturnAndException() {
        assertEquals(emptyMap(), LogContext.snapshot())
        LogContext.run(mapOf("tenantId" to "1")) {
            assertEquals("1", LogContext.snapshot()["tenantId"])
            assertFails { LogContext.call(mapOf("tenantId" to "2")) { assertEquals("2", LogContext.snapshot()["tenantId"]); error("expected") } }
            assertEquals("1", LogContext.snapshot()["tenantId"])
        }
        assertEquals(emptyMap(), LogContext.snapshot())
    }
    @Test fun captureOverridesAndRestoresWorkerMdc() {
        val mdc = LoggProvider.mdc
        mdc.put("tenantId", "1")
        var task: Runnable? = null
        LogContext.run(mapOf("tenantId" to "2")) { task = LogContext.capture { assertEquals("2", MDC.get("tenantId")); MDC.put("leak", "x") } }
        mdc.put("tenantId", "worker")
        task!!.run()
        assertEquals("worker", MDC.get("tenantId")); assertNull(MDC.get("leak"))
    }
    @Test fun threadPoolsDoNotInheritUncapturedContext() {
        Executors.newSingleThreadExecutor().use { pool ->
            LogContext.run(mapOf("tenantId" to "1")) {
                pool.submit { assertTrue(LogContext.snapshot().isEmpty()); assertNull(MDC.get("tenantId")) }.get()
                pool.submit(LogContext.capture { assertEquals("1", LogContext.snapshot()["tenantId"]) }).get()
            }
            pool.submit { assertTrue(LogContext.snapshot().isEmpty()); assertNull(MDC.get("tenantId")) }.get()
        }
    }
    @Test fun mdcMapsAndStacksAreDefensiveCopies() {
        val mdc = LoggMdc(); mdc.put("k", "v"); val copy = mdc.getCopyOfContextMap()!!; copy["k"] = "changed"; assertEquals("v", mdc.get("k"))
        mdc.pushByKey("s", "1"); val stack = mdc.getCopyOfDequeByKey("s")!!; stack.clear(); assertEquals("1", mdc.popByKey("s")); assertNull(mdc.popByKey("s"))
        mdc.clear(); assertNull(mdc.get("k"))
    }
    @Test fun invalidFieldsCannotThrowIntoTheApplication() {
        Logg.configure(LogConfig(directory))
        val broken = object : Iterable<String> { override fun iterator(): Iterator<String> = error("broken field") }
        LoggerFactory.getLogger("test").atInfo().addKeyValue("field", broken).log("still logged")
        Logg.shutdown()
        assertEquals("java.lang.IllegalStateException", rows().single()["fields"]["encodingFailure"].asString())
    }

}
