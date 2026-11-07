# How an embedding application owns tracing resources

A Java application that embeds the tracer uses one of two entry points:
`Tracer` for individual calls and `BatchTracer` for request streams. Both run the
same worker internally, but they divide ownership differently: what each call
creates, how long guest JVMs live, what can carry over between jobs, and who
closes what. This page explains those choices. The
[Java integration reference](../reference/java-api.md) defines the exact
contracts, and the [Java tutorial](../tutorials/java-integration.md) shows a
complete program.

## Every job spans the embedding JVM and a guest JVM

Tracing a job always involves two JVMs. The embedding application's JVM reads,
parses, and compiles the submitted source and prepares any binary dependencies.
It then launches a guest JVM that runs the compiled program while the embedding
JVM observes it through the Java Debug Interface. The guest uses the embedding
JVM's own JDK, which is why embedding needs a full JDK and permission to launch a
child JVM ([runtime requirements](../reference/java-api.md)).

Breakpoint discovery is the exception: `Tracer.discoverBreakpoints` compiles the
submitted classes and reads their line tables without launching a guest.

## A `Tracer` call owns everything it creates

`Tracer.trace` is self-contained. Each call creates its own worker, compilation
directory, parser state, trace session, and guest JVM, and it releases all of them
before returning. The returned `TraceOutcome` is detached: its metadata and JSON
remain valid after the guest is gone, and the caller has nothing to close.

For single-stack capture, the call uses the reusable guest harness, but only for
that one job; nothing is reused across calls. Multithread requests use a fresh
dedicated guest instead. The two guest kinds differ in lifetime: a reusable guest
ends the job when the entry method returns and interrupts lingering threads,
while a dedicated guest follows ordinary JVM lifetime. See
[guest modes](../BOUNDED_TRACING.md#guest-modes).

Because calls share nothing, independent `Tracer` calls may run concurrently from
multiple threads. The tracer applies no concurrency limit of its own, and each
concurrent trace launches its own guest JVM, so the caller decides how many to
run at once ([concurrent calls](../reference/java-api.md#concurrent-calls)).

## A `BatchTracer` owns long-lived workers

`BatchTracer` is built for throughput. A processor owns a fixed pool of workers
and a scheduler that admits a bounded number of requests at a time. Each
single-stack worker keeps a reusable guest JVM across jobs and replaces it after
its configured number of completed jobs, or sooner when a guest must be replaced.
Reusing guests avoids starting a JVM for every job.

The processor lives until the caller closes it, usually through
try-with-resources. It can process consecutive streams, but only one
`processStream` call at a time. The caller keeps ownership of each input stream
and writer: the processor flushes responses but never closes them.

The settings bound scheduling, not execution. Worker and admission counts limit
how many jobs run and wait at once; they do not limit what any job does. Batch
requests follow the [batch protocol](../reference/batch.md)'s defaults, under which
omitted limits are unlimited, rather than the finite defaults of a `TraceRequest`.

## Reuse trades isolation between jobs for speed

A reusable guest separates jobs by class loader and thread group, not by
process. Between jobs, the harness restores system properties, default locales,
the default time zone, standard streams, and the default uncaught-exception
handler. Other JDK-global state, and any filesystem changes, can remain visible
to later jobs on the same guest
([guest reuse](../reference/batch.md#guest-reuse-between-jobs)).

Setting `maxJobsPerWorker(1)` gives every executed job a fresh guest JVM, at the
cost of a JVM start per job. It removes reuse between jobs; it does not isolate
the embedding JVM, which still parses and compiles every submission, or the
host filesystem.

## Time budgets cover tracing, not preparation

The tracing deadline starts when a job enters the trace phase. Reading, parsing,
and compiling source, preparing dependencies, and starting a reusable guest all
happen before that, so the deadline does not bound them. A dedicated guest's
launch falls inside the trace phase. The [limits](../BOUNDED_TRACING.md#limits)
explain each budget; whole-job time limits belong to the caller or a runner.

## Shutdown drains what was admitted

Closing a processor shuts down its scheduler, waits for admitted jobs within
bounded intervals, interrupts them if necessary, and then closes the workers and
their guests. Admitted jobs can still write responses during that shutdown.

An I/O failure in `processStream` follows the same pattern. The method throws, but
admitted jobs are neither cancelled nor awaited, so they can keep writing to the
same writer. Keep that writer dedicated to the processor, then close the
processor ([I/O failures and shutdown](../reference/java-api.md#io-failures-and-shutdown)).

## Ownership is not isolation

Every arrangement on this page runs submitted code with the embedding
application's identity, environment, and files. Concurrent calls, reused or
fresh guests, and separate processors share the embedding process and the host.
Hosted untrusted submissions need one disposable tracing process per job inside
the isolation boundary that the [runner contract](../RUNNER_CONTRACT.md)
describes. [Why the tracer is not a sandbox](trust-model.md) explains the reasons.
