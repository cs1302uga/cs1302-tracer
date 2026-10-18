# Migrate Java integrations from 3.x

Version 4.0.0 introduces a breaking Java interface change. The supported interface
is `cs1302.tracer.api`; replacing 3.x helper calls changes failure handling,
default budgets, and selected-breakpoint result presentation as described below.
CLI and NDJSON entry points retain their own contracts; their defaults are not
inferred from the new Java convenience constructor. The primitive binary metadata
extension introduced in 4.1.0 remains opt-in.

| Previous integration | Replacement |
| --- | --- |
| `CompilationHelper` and `App.parseSource` | Submit source text to `Tracer`; compilation and AST ownership are internal. |
| `DebugTraceHelper` and JDI-based extraction helpers | `Tracer.trace(TraceRequest)` for self-contained jobs. |
| `TraceSession` limits, inspection, accumulation, or `enableMultithread()` | Supply the corresponding `TraceRequest` settings: `limits`, `inspection`, `accumulate`, and `multithread`. |
| `TraceSession.current()` | No supported session accessor replaces this call; one-shot tracing owns and closes its internal session. |
| `TraceSession.cancel()` | No supported per-trace session handle or cancellation method replaces this call. Use finite limits and, for hosted jobs, the runner's whole-job cancellation boundary. |
| `execution.TraceResult` | Detached `TraceOutcome` metadata and serialized result/payload JSON. |
| `PersistentGuestSession.create()` and its tracing methods | Use `Tracer.trace` for a one-shot operation or closeable `BatchTracer` for streamed jobs; no guest/JDI handle is exposed. |
| `getValidBreakpointLines` and compiled-program inspection | `Tracer.discoverBreakpoints(source)`. |
| `PyTutorSerializer.getGson().toJson(trace)` or `ModernTraceSerializer.getGson().toJson(trace)` | `TraceOutcome.traceJson()` for the bare trace payload. `json()` and `writeJson(Writer)` produce a result envelope instead. |
| `BatchTraceService`, including its two-argument constructor | `BatchTracer` with explicit worker, recycling, admission, and ordering settings and the same NDJSON stream protocol. |
| `execution.TraceLimits` / `execution.InspectionPolicy` | Corresponding types in `cs1302.tracer.api`. |
| `model.TraceFormat` / `model.TypeStyle` | Corresponding types in `cs1302.tracer.api`. |
| Internal snapshot or wire records | Consume the documented JSON contract; internal records are not supported DTOs. |

## Replace exception-based failure handling

Do not migrate only the method call and continue assuming that a returned value
means guest execution succeeded. In 3.x, helper compilation failures and session
stops could escape as exceptions. `Tracer.trace` represents expected preparation
failures, observed execution failures and controlled stops in a detached
`TraceOutcome`. Inspect `status()`, `complete()`, `stopReason()`, and diagnostics
before consuming partial results. Null request/dependency arguments still violate
the Java API's preconditions and can throw.

`new TraceRequest(source)` and `TraceRequest.builder(source)` select finite
instructor budgets, including a 10-second tracing deadline and 1 MiB per guest
output stream. Old helper calls made without a tracing session had no such
budgets. Use explicit `TraceLimits`, or `TraceLimits.unlimited()` when that is
the intended trusted-workload policy; fixed representation checks still apply.
The deadline excludes source preparation and reusable-guest startup. See the
[request defaults](../reference/java-api.md#trace-a-submitted-program) and
[budget rules](../BOUNDED_TRACING.md#limits).

There is a current single-stack exception-recognition limitation: exceptions
thrown inside JDK or precompiled dependency methods can appear in guest stderr
while completion metadata remains successful. Neither status alone nor stderr
text proves successful guest execution. Preserve captured output and read the
[exception contract](../reference/batch.md#uncaught-student-exceptions).

## Choose the required JSON document

`traceJson()` is the payload-oriented replacement for serializing a 3.x trace.
`json()` and `writeJson(Writer)` instead expose the
[version 1 result envelope](../BOUNDED_TRACING.md#envelope), containing status,
limits, diagnostics and the trace payload. They are not interchangeable inputs
to an existing payload renderer. `traceJson()` is the JSON literal `null` when
no payload is available; use the envelope's status and
[partial-result rules](../BOUNDED_TRACING.md#envelope), not that literal alone,
to classify the job.

The supported Java API returns a single sequence payload for selected
breakpoints. It does not reproduce a 3.x helper's line-keyed snapshot map or the
ordinary CLI's selected-breakpoint dictionary. Consumers that expect a root such
as `{"4": ...}` must adapt to the sequence or keep using the CLI entry point
whose documented output shape they need. See
[selected breakpoints and envelopes](../reference/output.md#selected-breakpoints-and-envelopes).

## Preserve batch settings explicitly

The former two-argument `BatchTraceService(workers, maxJobsPerWorker)` used
`Math.max(16, workers * 2)` admitted jobs and submission-order output. For the
same positive configured values, the explicit replacement is
`new BatchTracer(workers, maxJobsPerWorker, Math.max(16, workers * 2), false)`.
Use try-with-resources to release the processor. Its NDJSON records use
[batch defaults](../reference/batch.md#request-limits), including unlimited omitted
job limits, rather than the finite `TraceRequest` defaults.

Single-stack Java and batch calls use the reusable harness: threads remaining
after the entry method returns are interrupted, and `System.exit(0)` can become
`guest_exit`. Batch guests can also retain JDK-global state between jobs. Review
[guest reuse](../reference/batch.md#guest-reuse-between-jobs) before assuming the
same lifecycle as a dedicated CLI guest.

## Keep supported interfaces separate from implementation

Implementation classes now live under `cs1302.tracer.internal`. Some must remain
public for communication across implementation packages and guest JVM launching.
Their visibility does not make them supported library interfaces. There are no
compatibility aliases for the old parser/debugger/serializer helpers.

`Automatic-Module-Name` supplies stable module identity, not access control:
automatic modules export all packages, and classpath execution likewise does not
hide public internal classes. Explicit JPMS exports are deferred; doing that
requires validating dependency module names, Gson reflective access, and guest
launching independently for the regular JAR and executable assembly. The build
instead checks the supported declarations for dependency leakage and compiles an
external consumer with only tracer classes and API Guardian on its compile classpath.

Generated public **Javadoc** omits implementation packages. The repository's internal
package documentation and dependency links remain useful for contributor builds.
The tracer executes guest code and is not a security sandbox; use the separate
[runner contract](../RUNNER_CONTRACT.md) for isolation requirements.
