# Logg

Focused Kotlin/JDK 27 structured logging for applications we own. SLF4J remains the
library compatibility API. No XML, pattern language, network shipper or reflective
entity serialization. The core has no Spring dependency.

```kotlin
Logg.configure(LogConfig(directory = Path.of("logs"), release = "my-release"))
LogContext.run(mapOf("tenantId" to "42")) {
    logger.atInfo().addKeyValue("event", "invoice.sent")
        .addKeyValue("invoiceId", 123).log("Invoice sent")
}
Logg.shutdown()
```

Artifacts: `no.beint.logg:core:0.1.0`, optional `no.beint.logg:spring-boot:0.1.0`.
The Spring adapter reads `logg.directory`, `logg.console`, `logg.release`,
`logg.retention-days`, `logg.max-file-bytes`, `logg.archive-bytes` and Boot logging levels
and groups. Remove other SLF4J providers. It does not require Actuator. Existing
`logging.file.*`/XML configuration is rejected rather than silently interpreted.

Each line is JSON with v, ts, instance, seq, level, logger, thread, release, message,
context, fields and optional bounded error/cause/root-cause/stack. Numbers and booleans
stay typed. instance+seq is stable across replay. Unsupported objects are represented
by type, not serialized. Scalar text is bounded; maps/lists and stack traces are bounded.
Invalid surrogate code units become U+FFFD; valid Unicode round-trips.

One writer uses a 512-record/16MiB admission budget. Producers fall back to stderr on
saturation instead of waiting for the writer. A batch remains owned until file flush
completes; write/flush failures replay the whole batch. Ambiguous writes can produce
duplicates: consumers deduplicate by instance+seq. Flush is not fsync. Abrupt process or
machine failure can lose queued/kernel-buffered records. This is operational logging,
not transactional audit storage.

Shutdown waits up to five seconds, then replays queued/in-flight records to fallback.
The writer is a daemon; it can complete later and create duplicates. Slow/unavailable
fallback streams remain an external I/O constraint; `Logg.status()` reports counts of
accepted, fallback, I/O failures, undelivered records and pending work. Both outputs
failing cannot be made lossless by a logging library.

The configured directory should be private and dedicated to Logg. Each instance owns
unique files and a lifetime lock; rotation is by UTC day/size, compression uses a
same-directory temporary file and atomic move, and cleanup excludes live writers.
Default history is 30 days and archive budget 1GiB; cleanup runs on startup/rotation and
hourly while the application runs. No legacy format or old-log migration is included.

`LogContext` uses immutable ScopedValue bindings. Capture explicitly when submitting to
executors (`LogContext.capture`); it also restores worker MDC. Scoped tenant overrides
win over request MDC. No credentials, headers, bodies or entire domain objects belong
in context/fields.

Build/tests: `./gradlew clean build`. JMH: `./gradlew :core:jmhJar`, then run the
executable JAR with forks, warmups and `-prof gc`. Public CI runs tests on Linux/Windows
and uploads JUnit/JMH results. Benchmarks do not gate on noisy elapsed-time thresholds.
