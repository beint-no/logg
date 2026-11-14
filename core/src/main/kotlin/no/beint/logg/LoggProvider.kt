package no.beint.logg

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

class LoggProvider : SLF4JServiceProvider {
    companion object {
        internal val mdc = LoggMdc()
    }

    private val loggers = ConcurrentHashMap<String, LoggLogger>()
    private val markers = BasicMarkerFactory()
    private val factory = ILoggerFactory { name -> loggers.computeIfAbsent(name, ::LoggLogger) }

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}
