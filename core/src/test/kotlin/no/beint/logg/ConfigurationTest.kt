package no.beint.logg

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class ConfigurationTest {
    @ParameterizedTest @ValueSource(ints = [-1, 0, 61, 1000])
    fun invalidRetentionIsRejected(days: Int) { assertFailsWith<IllegalArgumentException> { LogConfig(retentionDays = days) } }
    @ParameterizedTest @ValueSource(longs = [-1, 0, 1023])
    fun invalidFileSizesAreRejected(bytes: Long) { assertFailsWith<IllegalArgumentException> { LogConfig(maxFileBytes = bytes) } }
    @ParameterizedTest @ValueSource(longs = [-1, 0, 30001])
    fun invalidShutdownTimeoutIsRejected(millis: Long) { assertFailsWith<IllegalArgumentException> { LogConfig(shutdownTimeout = Duration.ofMillis(millis)) } }
    @Test fun disabledAllOutputIsRejected() { assertFailsWith<IllegalArgumentException> { LogConfig(console = false) } }
    @Test fun archiveCapMustCoverOneFile() { assertFailsWith<IllegalArgumentException> { LogConfig(maxFileBytes = 2048, archiveBytes = 1024) } }
    @Test fun fileConfigurationDoesNotDuplicateConsoleByDefault() { assertFalse(LogConfig(Path.of("logs")).console); assertTrue(LogConfig().console) }
}
