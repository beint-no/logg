package no.beint.logg

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.Level as LogbackLevel
import org.openjdk.jmh.annotations.*
import org.slf4j.Logger
import org.slf4j.event.Level
import org.slf4j.helpers.NOPMDCAdapter
import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.EncoderBase
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class LoggingBenchmark {
    private lateinit var own: Logger
    private lateinit var baseline: Logger
    private lateinit var context: LoggerContext
    private lateinit var asynchronous: Logger
    private lateinit var sink: LogSink
    private val encoded = "{\"event\":\"invoice.sent\",\"invoiceId\":123}\n"
    @Setup fun setup() {
        own = LoggProvider().loggerFactory.getLogger("benchmark")
        context = LoggerContext().apply { mdcAdapter = NOPMDCAdapter() }
        baseline = context.getLogger("benchmark").apply { level = LogbackLevel.INFO }
        val encoder = object : EncoderBase<ILoggingEvent>() {
            override fun headerBytes(): ByteArray? = null
            override fun footerBytes(): ByteArray? = null
            override fun encode(event: ILoggingEvent): ByteArray = event.formattedMessage.toByteArray(Charsets.UTF_8)
        }.apply { context = this@LoggingBenchmark.context; start() }
        val output = OutputStreamAppender<ILoggingEvent>().apply {
            context = this@LoggingBenchmark.context
            this.encoder = encoder
            outputStream = OutputStream.nullOutputStream()
            start()
        }
        val async = AsyncAppender().apply {
            context = this@LoggingBenchmark.context; queueSize = 512; discardingThreshold = 0; maxFlushTime = 5000
            addAppender(output); start()
        }
        asynchronous = context.getLogger("asynchronous").apply { level = LogbackLevel.INFO; isAdditive = false; addAppender(async) }
        sink = LogSink(LogConfig(), consoleOutput = OutputStream.nullOutputStream(), fallbackOutput = OutputStream.nullOutputStream())
    }
    @TearDown fun close() { sink.close(); context.stop() }
    @Benchmark fun loggDisabled() { own.debug("invoice {}", 123) }
    @Benchmark fun logbackDisabled() { baseline.debug("invoice {}", 123) }
    @Benchmark fun encodeWideEvent(): String = JsonEvent.encode("benchmark", Level.INFO, "Invoice sent", null,
        mapOf("tenantId" to "42", "requestId" to "123"), mapOf("event" to "invoice.sent", "invoiceId" to 123, "durationMs" to 5), "benchmark")
    @Benchmark @Threads(8) fun boundedDeliveryToNullStream() { sink.submit(encoded) }
    @Benchmark @Threads(8) fun logbackDeliveryToNullStream() { asynchronous.info(encoded) }
}
