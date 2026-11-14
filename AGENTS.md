# Logg

Kotlin/JDK 27, SLF4J 2. Keep the core independent of Spring, HTTP, deployment systems and
application domains. Spring Boot belongs only in spring-boot; benchmarks and test-only
libraries must never enter published runtime dependencies.

Committed tests and benchmarks are required here. Every fixed delivery, encoding,
retention, lifecycle or facade defect gets a regression test. Fault-inject before
claiming failure guarantees; test subprocess crashes and multiple processes as well as
threads. Build and run public CI before release. Publish immutable versioned artifacts.

Delivery is best effort to file or fallback, not a durable audit ledger. Retain batches
until flush succeeds. Ambiguous failures and shutdown deadlines may replay duplicates;
instance+seq identifies a record across copies. Report unavailable fallback through
status; never throw a logging I/O failure into an application's call path. Bound caller
queue admission and writer shutdown; arbitrary external stream I/O itself may block.

Use JMH forks/warmup/allocation profiling and equivalent encoding/output/flush policies.
Do not describe memory-sink throughput as production file latency, compare noisy CI
machines as regressions, or claim speedups without reporting uncertainty and durability
tradeoffs. CI benchmarks are smoke checks and artifacts, not timing-based pass/fail gates.

No application-specific code, private fixtures, logs, secrets or credentials in this
public repository. Existing operator publishing credentials stay in environment/GitHub
Secrets. Work in ~/.r-worktrees; keep ~/r/logg on main. Squash PRs and delete branches.
