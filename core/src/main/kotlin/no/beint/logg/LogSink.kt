package no.beint.logg

import java.io.OutputStream
import java.time.Clock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class LogSink(
    private val config: LogConfig,
    private val fallbackOutput: OutputStream = System.err,
    private val consoleOutput: OutputStream = System.out,
    clock: Clock = Clock.systemUTC(),
    outputFactory: ((java.nio.file.Path) -> OutputStream)? = null,
    capacity: Int = 512,
    private val byteLimit: Int = 16 * 1024 * 1024,
) : AutoCloseable {
    private class Batch(val records: List<ByteArray>) { val replayed = AtomicBoolean() }
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val budget = Semaphore(byteLimit)
    private val admission = ReentrantLock()
    private val files = config.directory?.let { LogFiles(it, config, clock, outputFactory) }
    private val accepted = LongAdder()
    private val fallback = LongAdder()
    private val failures = LongAdder()
    private val undelivered = LongAdder()
    private val inFlight = AtomicReference<Batch?>()
    @Volatile private var closing = false
    private val writer = Thread.ofPlatform().daemon(true).name("logg-writer").unstarted(::drain).also(Thread::start)

    fun submit(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reserved = bytes.size <= byteLimit && budget.tryAcquire(bytes.size)
        val enqueued = reserved && admission.withLock { !closing && queue.offer(bytes) }
        if (enqueued) { accepted.increment(); LockSupport.unpark(writer) } else {
            if (reserved) budget.release(bytes.size)
            replay(listOf(bytes))
        }
    }

    fun status() = LogStatus(accepted.sum(), fallback.sum(), failures.sum(), undelivered.sum(), queue.size, byteLimit - budget.availablePermits(), writer.isAlive)

    private fun replay(records: List<ByteArray>) {
        try {
            synchronized(fallbackOutput) {
                records.forEach(fallbackOutput::write)
                fallbackOutput.flush()
                if (fallbackOutput is java.io.PrintStream && fallbackOutput.checkError()) throw java.io.IOException("Fallback stream failed")
            }
            fallback.add(records.size.toLong())
        } catch (_: Exception) { undelivered.add(records.size.toLong()) }
    }

    private fun replay(batch: Batch) {
        if (batch.replayed.compareAndSet(false, true)) replay(batch.records)
    }

    private fun drain() {
        try {
            while (!closing || queue.isNotEmpty()) {
                val batch = admission.withLock {
                    val records = ArrayList<ByteArray>(256)
                    queue.drainTo(records, 256)
                    if (records.isEmpty()) null else Batch(records).also(inFlight::set)
                }
                if (batch == null) {
                    try { files?.cleanupIfDue() } catch (_: Exception) { failures.increment() }
                    LockSupport.parkNanos(100_000_000)
                    if (Thread.interrupted()) throw InterruptedException()
                    continue
                }
                val records = batch.records
                try {
                    if (config.console) synchronized(consoleOutput) {
                        records.forEach(consoleOutput::write)
                        consoleOutput.flush()
                    }
                    if (files != null) {
                        records.forEach(files::write)
                        files.flush()
                    }
                } catch (error: Exception) {
                    failures.increment()
                    replay(batch)
                    files?.discardBufferedOutput()
                } finally {
                    inFlight.compareAndSet(batch, null)
                    records.forEach { budget.release(it.size) }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            failures.increment()
        } finally {
            admission.withLock { closing = true }
            inFlight.get()?.let(::replay)
            drainPending()
            try { files?.close() } catch (_: Exception) { failures.increment() }
        }
    }

    private fun drainPending() {
        val pending = ArrayList<ByteArray>()
        queue.drainTo(pending)
        if (pending.isNotEmpty()) {
            try { replay(pending) } finally { pending.forEach { budget.release(it.size) } }
        }
    }

    override fun close() {
        admission.withLock { closing = true }
        LockSupport.unpark(writer)
        if (Thread.currentThread() === writer) return
        val deadline = System.nanoTime() + config.shutdownTimeout.toNanos()
        var interrupted = false
        while (writer.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try { writer.join(java.time.Duration.ofNanos(remaining)) } catch (_: InterruptedException) { interrupted = true }
        }
        if (writer.isAlive) {
            val pending = ArrayList<ByteArray>()
            val batch = admission.withLock { queue.drainTo(pending); inFlight.get() }
            batch?.let(::replay)
            try { replay(pending) } finally { pending.forEach { budget.release(it.size) } }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
