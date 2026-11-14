package no.beint.logg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DeliveryTest {
    @TempDir lateinit var directory: Path
    private fun config(timeout: Long = 5000) = LogConfig(directory, shutdownTimeout = Duration.ofMillis(timeout))
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (!check()) { check(System.nanoTime() < deadline); Thread.sleep(1) }
    }

    @ParameterizedTest @ValueSource(ints = [1, 2, 8, 32])
    fun concurrentRecordsAreDrainedOnce(producers: Int) {
        val output = ByteArrayOutputStream()
        val fallback = ByteArrayOutputStream()
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = { output })
        Executors.newFixedThreadPool(producers).use { pool ->
            repeat(producers) { id -> pool.submit { repeat(100) { sink.submit("$id:$it\n") } } }
        }
        sink.close()
        val records = (output.toString() + fallback).lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(producers * 100, records.size)
        assertEquals(records.size, records.toSet().size)
        assertEquals(0, sink.status().pendingBytes)
        assertFalse(sink.status().writerAlive)
    }

    @ParameterizedTest @ValueSource(strings = ["write", "flush"])
    fun wholeAcceptedBatchIsReplayedOnIoFailure(stage: String) {
        val fallback = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) { if (stage == "write") throw IOException("full") }
            override fun flush() { if (stage == "flush") throw IOException("full on flush") }
        }
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = { output })
        repeat(40) { sink.submit("probe:$it\n") }
        sink.close()
        assertEquals((0 until 40).map { "probe:$it" }.toSet(), fallback.toString().lines().filter(String::isNotEmpty).toSet())
        assertEquals(40, sink.status().fallback)
        assertEquals(0, sink.status().pendingBytes)
        assertTrue(sink.status().ioFailures > 0)
    }

    @Test fun failedFileOutputRecoversForLaterRecords() {
        val fallback = ByteArrayOutputStream()
        val recovered = ByteArrayOutputStream()
        var opens = 0
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = {
            if (opens++ == 0) object : OutputStream() { override fun write(value: Int) { throw IOException("full") } } else recovered
        })
        sink.submit("failed\n")
        await { sink.status().fallback == 1L }
        sink.submit("recovered\n")
        sink.close()
        assertEquals("failed\n", fallback.toString())
        assertEquals("recovered\n", recovered.toString())
    }

    @Test fun shutdownTimeoutReplaysInFlightAndQueuedRecords() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fallback = ByteArrayOutputStream()
        val sink = LogSink(config(50), fallbackOutput = fallback, outputFactory = {
            object : OutputStream() { override fun write(value: Int) { entered.countDown(); release.await() } }
        })
        sink.submit("inflight\n")
        assertTrue(entered.await(5, SECONDS))
        sink.submit("queued\n")
        val start = System.nanoTime()
        sink.close()
        assertTrue(System.nanoTime() - start < SECONDS.toNanos(2))
        assertEquals(setOf("inflight", "queued"), fallback.toString().lines().filter(String::isNotEmpty).toSet())
        sink.close()
        assertEquals(2, fallback.toString().lines().count(String::isNotEmpty))
        release.countDown()
        await { !sink.status().writerAlive }
    }

    @ParameterizedTest @ValueSource(ints = [1, 2, 8])
    fun fullQueueUsesFallbackWithoutWaitingForWriter(capacity: Int) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fallback = ByteArrayOutputStream()
        val sink = LogSink(config(20), fallbackOutput = fallback, outputFactory = {
            object : OutputStream() { override fun write(value: Int) { entered.countDown(); release.await() } }
        }, capacity = capacity)
        sink.submit("inflight\n")
        assertTrue(entered.await(5, SECONDS))
        repeat(100) { sink.submit("queued:$it\n") }
        assertTrue(sink.status().fallback >= 100 - capacity)
        sink.close()
        release.countDown()
        await { !sink.status().writerAlive }
        assertEquals(101, fallback.toString().lines().count(String::isNotEmpty))
    }

    @Test fun byteBudgetAndOversizedRecordsUseFallback() {
        val fallback = ByteArrayOutputStream()
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = { ByteArrayOutputStream() }, byteLimit = 4)
        sink.submit("larger than budget\n")
        sink.close()
        assertEquals("larger than budget\n", fallback.toString())
        assertEquals(0, sink.status().pendingBytes)
    }

    @Test fun unavailableFallbackIsObservableAndDoesNotThrowIntoCaller() {
        val bad = object : OutputStream() { override fun write(value: Int) { throw IOException("unavailable") } }
        val sink = LogSink(config(), fallbackOutput = bad, byteLimit = 1)
        sink.submit("oversized\n")
        sink.close()
        assertEquals(1, sink.status().undelivered)
    }

    @Test fun concurrentShutdownDoesNotLoseAcceptedRecords() {
        val output = ByteArrayOutputStream()
        val fallback = ByteArrayOutputStream()
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = { output })
        val started = AtomicBoolean()
        Executors.newFixedThreadPool(8).use { pool ->
            repeat(7) { id -> pool.submit { started.set(true); repeat(500) { sink.submit("$id:$it\n") } } }
            pool.submit { while (!started.get()) Thread.onSpinWait(); sink.close() }
        }
        sink.close()
        val records = (output.toString() + fallback).lines().filter(String::isNotEmpty)
        assertEquals(3500, records.size)
        assertEquals(records.size, records.toSet().size)
    }
    @Test fun partialWritesDoNotCorruptSubsequentRecords() {
        val fallback = ByteArrayOutputStream()
        var opens = 0
        val sink = LogSink(config(), fallbackOutput = fallback, outputFactory = { path ->
            if (opens++ == 0) {
                val file = java.nio.file.Files.newOutputStream(path, java.nio.file.StandardOpenOption.APPEND)
                object : OutputStream() {
                    var written = 0
                    override fun write(value: Int) { if (written++ >= 5) throw IOException("partial"); file.write(value) }
                    override fun close() { file.close() }
                }
            } else java.nio.file.Files.newOutputStream(path, java.nio.file.StandardOpenOption.APPEND)
        })
        sink.submit("first-record\n")
        await { sink.status().fallback == 1L }
        sink.submit("second-record\n")
        sink.close()
        val lines = java.nio.file.Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".active.jsonl") }.flatMap(java.nio.file.Files::lines).toList() }
        assertTrue("second-record" in lines)
        assertEquals("first-record\n", fallback.toString())
    }

}
