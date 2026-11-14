package no.beint.logg

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import java.util.UUID
import java.util.zip.GZIPOutputStream

internal class LogFiles(
    directory: Path, private val config: LogConfig, private val clock: Clock,
    private val outputFactory: ((Path) -> OutputStream)? = null,
) : AutoCloseable {
    companion object {
        private val activeName = Regex("[a-f0-9-]{36}\\.active\\.jsonl")
        private val archiveName = Regex("\\d{4}-\\d{2}-\\d{2}-[a-f0-9-]{36}-\\d+\\.jsonl\\.gz")
    }
    private val directory = directory.toAbsolutePath()
    private val id = UUID.randomUUID().toString()
    private val active = this.directory.resolve("$id.active.jsonl")
    private val lockPath = this.directory.resolve("$id.lock")
    private val channel: FileChannel
    private val lock: FileLock
    private var output: OutputStream? = null
    private var recovering = false
    private var bytes = 0L
    private var day = LocalDate.now(clock.withZone(UTC))
    private var index = 0
    private var nextCleanup = Instant.MIN

    init {
        Files.createDirectories(this.directory)
        channel = FileChannel.open(lockPath, CREATE, WRITE)
        var acquired: FileLock? = null
        try {
            acquired = channel.tryLock() ?: error("Cannot lock unique log instance")
            lock = acquired
            Files.createFile(active)
            open()
            cleanupIfDue()
        } catch (error: Exception) {
            try { output?.close() } finally { acquired?.release(); channel.close(); Files.deleteIfExists(lockPath) }
            throw error
        }
    }

    private fun open() {
        output = outputFactory?.invoke(active) ?: BufferedOutputStream(
            Files.newOutputStream(active, java.nio.file.StandardOpenOption.APPEND), 65_536)
        bytes = Files.size(active)
        if (recovering && bytes > 0) { requireNotNull(output).write('\n'.code); bytes++ }
        recovering = false
    }

    fun write(line: ByteArray) {
        if (output == null) open()
        if (bytes > 0 && (bytes + line.size > config.maxFileBytes || day != LocalDate.now(clock.withZone(UTC)))) rotate()
        requireNotNull(output).write(line)
        bytes += line.size
    }

    fun flush() { output?.flush() }

    fun discardBufferedOutput() {
        val previous = output
        output = null
        recovering = true
        try { previous?.close() } catch (_: Exception) { }
    }

    private fun rotate() {
        requireNotNull(output).close()
        output = null
        val archive = directory.resolve("$day-$id-${index++}.jsonl.gz")
        val temporary = directory.resolve("${archive.fileName}.part")
        val modified = Files.getLastModifiedTime(active)
        try {
            GZIPOutputStream(Files.newOutputStream(temporary)).use { Files.copy(active, it) }
            Files.setLastModifiedTime(temporary, modified)
            Files.move(temporary, archive, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            Files.newOutputStream(active, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).close()
        } finally {
            Files.deleteIfExists(temporary)
            day = LocalDate.now(clock.withZone(UTC))
            open()
        }
        cleanupIfDue(force = true)
    }

    fun cleanupIfDue(force: Boolean = false) {
        val now = clock.instant()
        if (!force && now < nextCleanup) return
        nextCleanup = now.plusSeconds(3600)
        val cutoff = now.minusSeconds(config.retentionDays * 86400L)
        data class Candidate(val path: Path, val owner: String?, val modified: Instant, val size: Long)
        val candidates = Files.list(directory).use { paths ->
            paths.filter { it != active && Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { path ->
                val name = path.fileName.toString()
                when {
                    archiveName.matches(name) -> Candidate(path, null, modified(path), size(path))
                    activeName.matches(name) -> Candidate(path, name.removeSuffix(".active.jsonl"), modified(path), size(path))
                    name.endsWith(".jsonl.gz.part") && archiveName.matches(name.removeSuffix(".part")) ->
                        Candidate(path, name.substring(11, 47), modified(path), size(path))
                    else -> null
                }
            }.filter { it != null }.map { requireNotNull(it) }.filter { it.owner == null || unlocked(it.owner) { } }
                .sorted(compareBy { it.modified }).toList()
        }
        var total = candidates.sumOf { it.size }
        for (candidate in candidates) if (candidate.modified < cutoff || total > config.archiveBytes) {
            val remove = { if (Files.deleteIfExists(candidate.path)) total -= candidate.size; Unit }
            if (candidate.owner == null) remove() else {
                unlocked(candidate.owner, remove)
                if (!Files.exists(candidate.path)) Files.deleteIfExists(directory.resolve("${candidate.owner}.lock"))
            }
        }
    }

    private fun unlocked(owner: String, action: () -> Unit): Boolean {
        try {
            FileChannel.open(directory.resolve("$owner.lock"), CREATE, WRITE).use { other ->
                other.tryLock()?.use { action(); return true }
            }
        } catch (_: OverlappingFileLockException) { }
        return false
    }

    private fun modified(path: Path): Instant =
        try { Files.getLastModifiedTime(path).toInstant() } catch (_: NoSuchFileException) { Instant.MIN }
    private fun size(path: Path): Long = try { Files.size(path) } catch (_: NoSuchFileException) { 0 }

    override fun close() {
        try { output?.close() } finally {
            try { lock.release() } finally { channel.close(); Files.deleteIfExists(lockPath) }
        }
    }
}
