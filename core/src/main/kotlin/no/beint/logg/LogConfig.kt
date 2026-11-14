package no.beint.logg

import java.nio.file.Path
import java.time.Duration

data class LogConfig(
    val directory: Path? = null,
    val console: Boolean = directory == null,
    val maxFileBytes: Long = 50L * 1024 * 1024,
    val retentionDays: Int = 30,
    val archiveBytes: Long = 1024L * 1024 * 1024,
    val release: String = "local",
    val shutdownTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(directory != null || console) { "At least one log output is required" }
        require(maxFileBytes >= 1024 && archiveBytes >= maxFileBytes)
        require(retentionDays in 1..60)
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero && shutdownTimeout <= Duration.ofSeconds(30))
    }
}

data class LogStatus(val accepted: Long, val fallback: Long, val ioFailures: Long, val undelivered: Long, val queued: Int, val pendingBytes: Int, val writerAlive: Boolean)
