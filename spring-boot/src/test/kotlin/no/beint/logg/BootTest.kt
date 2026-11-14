package no.beint.logg

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.boot.logging.LoggingSystem
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.*

class BootTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun reset() { Logg.shutdown(); Logg.setLevel("test", null) }
    private fun environment(properties: Map<String, Any>) = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("test", properties)) }
    @Test fun bootDiscoversOnlyOurFactory() { assertIs<LoggLoggingSystem>(LoggingSystem.get(javaClass.classLoader)) }
    @Test fun preparedEnvironmentConfiguresFileLoggingAndJul() {
        val system = LoggLoggingSystem()
        system.initialize(LoggingInitializationContext(environment(mapOf("logg.directory" to directory.toString()))), null, null)
        Logger.getLogger("test.jul").warning("jul record")
        Logg.shutdown()
        val lines = Files.list(directory).use { it.filter { path -> path.toString().endsWith(".active.jsonl") }.flatMap(Files::lines).toList() }
        assertEquals(1, lines.size); assertTrue(lines.single().contains("jul record"))
    }
    @ParameterizedTest @EnumSource(LogLevel::class)
    fun everyBootLevelMapsToFacadeAndJul(level: LogLevel) {
        LoggLoggingSystem().setLogLevel("test", level)
        val logger = LoggerFactory.getLogger("test.child")
        assertEquals(level != LogLevel.OFF, logger.isErrorEnabled)
        assertEquals(level in setOf(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO), logger.isInfoEnabled)
    }
    @Test fun xmlConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { LoggLoggingSystem().initialize(LoggingInitializationContext(environment(emptyMap())), "logback.xml", null) }
    }
    @Test fun invalidRetentionFailsStartup() {
        assertFailsWith<IllegalArgumentException> { LoggLoggingSystem().initialize(LoggingInitializationContext(environment(mapOf("logg.retention-days" to "0"))), null, null) }
    }
    @Test fun adapterDoesNotRequireActuator() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.springframework.boot.actuate.endpoint.annotation.Endpoint") }
    }
}
