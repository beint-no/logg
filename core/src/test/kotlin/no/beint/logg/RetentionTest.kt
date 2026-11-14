package no.beint.logg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.*
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.test.*

class RetentionTest {
    @TempDir lateinit var directory: Path
    private class Time(var now: Instant = Instant.now()) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun records() = Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".jsonl") || it.toString().endsWith(".gz") }.flatMap { path ->
        (if (path.toString().endsWith(".gz")) GZIPInputStream(Files.newInputStream(path)).bufferedReader() else Files.newBufferedReader(path)).lines()
    }.toList() }

    @ParameterizedTest @ValueSource(ints = [1024, 4096, 16384])
    fun sizeRotationPreservesWholeRecords(limit: Int) {
        val files = LogFiles(directory, LogConfig(directory, maxFileBytes = limit.toLong()), Time())
        repeat(100) { files.write("$it:${"x".repeat(200)}\n".toByteArray()) }
        files.close()
        val records = records()
        assertEquals(100, records.size)
        assertEquals((0 until 100).toSet(), records.map { it.substringBefore(':').toInt() }.toSet())
        assertTrue(Files.list(directory).use { paths -> paths.anyMatch { it.toString().endsWith(".gz") } })
    }
    @Test fun utcDayRotationAndArchiveTimestamp() {
        val time = Time(Instant.parse("2026-01-01T23:59:59Z"))
        val files = LogFiles(directory, LogConfig(directory), time)
        files.write("first\n".toByteArray()); files.flush()
        val active = Files.list(directory).use { it.filter { p -> p.toString().endsWith(".active.jsonl") }.findFirst().orElseThrow() }
        Files.setLastModifiedTime(active, FileTime.from(time.now))
        time.now = time.now.plusSeconds(2)
        files.write("second\n".toByteArray()); files.close()
        val archive = Files.list(directory).use { it.filter { p -> p.toString().endsWith(".gz") }.findFirst().orElseThrow() }
        assertTrue(archive.fileName.toString().startsWith("2026-01-01"))
        assertEquals(Instant.parse("2026-01-01T23:59:59Z"), Files.getLastModifiedTime(archive).toInstant())
        assertEquals(setOf("first", "second"), records().toSet())
    }
    @ParameterizedTest @ValueSource(ints = [1, 30, 60])
    fun ageCleanupRemovesOnlyOwnedExpiredFiles(days: Int) {
        val time = Time()
        val expired = directory.resolve("2020-01-01-${UUID.randomUUID()}-0.jsonl.gz")
        Files.writeString(expired, "expired")
        Files.setLastModifiedTime(expired, FileTime.from(time.now.minusSeconds((days + 1) * 86400L)))
        val unrelated = directory.resolve("accounting.data"); Files.writeString(unrelated, "keep")
        LogFiles(directory, LogConfig(directory, retentionDays = days), time).close()
        assertFalse(Files.exists(expired)); assertTrue(Files.exists(unrelated))
    }
    @Test fun archiveCapDeletesOldestFiles() {
        val time = Time()
        repeat(6) { index ->
            val file = directory.resolve("2026-01-01-${UUID.randomUUID()}-0.jsonl.gz")
            Files.writeString(file, "x".repeat(1024))
            Files.setLastModifiedTime(file, FileTime.from(time.now.minusSeconds(3600L + index)))
        }
        LogFiles(directory, LogConfig(directory, maxFileBytes = 1024, archiveBytes = 2048), time).close()
        val archives = Files.list(directory).use { it.filter { p -> p.toString().endsWith(".gz") }.toList() }
        assertEquals(2, archives.size)
    }
    @Test fun liveWriterIsNeverDeletedEvenWhenOld() {
        val time = Time()
        val first = LogFiles(directory, LogConfig(directory), time)
        first.write("live\n".toByteArray()); first.flush()
        val active = Files.list(directory).use { it.filter { p -> p.toString().endsWith(".active.jsonl") }.findFirst().orElseThrow() }
        Files.setLastModifiedTime(active, FileTime.from(time.now.minusSeconds(61 * 86400L)))
        LogFiles(directory, LogConfig(directory), time).close()
        assertTrue(Files.exists(active))
        first.close()
        LogFiles(directory, LogConfig(directory), time).close()
        assertFalse(Files.exists(active))
    }
    @Test fun independentInstancesNeverShareActiveFile() {
        LogFiles(directory, LogConfig(directory), Time()).use { first ->
            LogFiles(directory, LogConfig(directory), Time()).use { second ->
                first.write("first\n".toByteArray()); second.write("second\n".toByteArray())
            }
        }
        assertEquals(setOf("first", "second"), records().toSet())
    }
    @Test fun closeRemovesItsLockFile() {
        LogFiles(directory, LogConfig(directory), Time()).close()
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.toString().endsWith(".lock") } })
    }
}
