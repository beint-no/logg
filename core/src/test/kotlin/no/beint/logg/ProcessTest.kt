package no.beint.logg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

object ProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        val output = LogFiles(Path.of(args[0]), LogConfig(Path.of(args[0])), java.time.Clock.systemUTC())
        output.write("child-record\n".toByteArray()); output.flush()
        println("READY")
        while (true) Thread.sleep(1000)
    }
}

class ProcessTest {
    @TempDir lateinit var directory: Path
    @Test fun killedProcessUnlocksFilesButLiveProcessIsProtected() {
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(executable.toString(), "-cp", System.getProperty("logg.test.classpath"), ProcessFixture::class.java.name, directory.toString()).redirectErrorStream(true).start()
        try {
            assertEquals("READY", process.inputReader().readLine())
            val active = Files.list(directory).use { it.filter { path -> path.toString().endsWith(".active.jsonl") }.findFirst().orElseThrow() }
            Files.setLastModifiedTime(active, FileTime.from(Instant.now().minusSeconds(61L * 86400)))
            LogFiles(directory, LogConfig(directory), java.time.Clock.systemUTC()).close()
            assertTrue(Files.exists(active))
            process.destroyForcibly(); assertTrue(process.waitFor(10, SECONDS))
            LogFiles(directory, LogConfig(directory), java.time.Clock.systemUTC()).close()
            assertFalse(Files.exists(active))
        } finally { process.destroyForcibly(); process.waitFor(10, SECONDS) }
    }
}
