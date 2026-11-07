# Bounded tracing and result schema v1

Ordinary instructor invocations use finite budgets while preserving successful
Python Tutor and modern JSON shapes. A limit stop writes no JSON to stdout, prints
its reason to stderr, and exits with code 3. Use `--result-envelope` for explicit
job status and completed partial snapshots. These settings do not isolate a program from the host.

Ordinary defaults are 10 seconds of tracing, 10,000 snapshots, 1 MiB of output per
stream, 10,000 heap objects and 100,000 elements per snapshot, 64 MiB of accounted
trace data, 1 MiB of submitted source, 128 streamed source files, and 8 MiB per
snapshot. Multithread capture additionally uses defaults of 64 live application
threads and 4,096 total frames per snapshot. These are
configurable interactive-workload defaults, not measured process-memory ceilings.
Source limits cover submitted input, not neighboring sources discovered on disk,
binary dependencies, or copied resource trees. Dependency selection and copying
run during preparation before compilation; the tracing deadline does not bound
that work. Whole-job resource limits remain the runner's responsibility.
The tracing deadline begins when execution enters the trace phase, after source
reading, parsing, and compilation. Dedicated guest launch is within that phase;
reusable guest startup happens before it and is not covered by this deadline.

Use `--unlimited` to disable these defaults. An explicit individual limit still
applies with `--unlimited`, regardless of option order; zero disables that limit.
Other entry points have their own omitted-limit profiles: CLI envelope and batch
requests leave omitted limits unlimited, while Java `TraceRequest` convenience
constructors use the finite defaults. The [guest modes](#guest-modes) table lists
each profile; supply intended budgets explicitly.
`--inspection FIELDS` still requires `--result-envelope`.
Hosted execution requires the separate [runner contract](RUNNER_CONTRACT.md).

```sh
java -jar target/code-tracer.jar trace \
  --result-envelope --inspection FIELDS -a -f pytutor \
  --timeout-ms 5000 --max-snapshots 1000 --max-output-bytes 65536 \
  --max-heap-objects 1000 --max-elements 10000 --max-trace-bytes 33554432 \
  --max-threads 64 --max-frames 4096 --max-snapshot-bytes 8388608 \
  --max-source-bytes 262144 --max-source-files 32 < submission.txt
```

This is an illustrative teaching-workload profile, not a security guarantee or a
universal production default. Tune it for your examples and the runner's total
memory, concurrency, and wall-clock budgets. Supply finite budgets for every
hosted job.
Negative values, timeouts greater than `Long.MAX_VALUE / 1_000_000` milliseconds,
and unsupported options are rejected before tracing.

## Guest modes

The guest is the JVM that executes submitted code. Its lifetime and default budget
profile depend on the entry point:

| Entry point | Guest lifetime | Budget defaults |
| --- | --- | --- |
| Ordinary CLI `trace` | A dedicated JVM for that invocation. | Finite instructor defaults listed above. |
| CLI `trace --result-envelope` | A dedicated JVM for that invocation. | Omitted limits are unlimited. |
| Java `Tracer.trace`, single-stack capture | The reusable guest harness, owned and closed by one API call. | `TraceRequest` convenience constructors and builders use finite instructor defaults; explicit requests supply their limits. |
| `batch-trace` or `BatchTracer`, single-stack capture | A reusable JVM per worker, recycled after its configured completed-job count or when replacement is needed. | Omitted request limits are unlimited. |
| Multithread capture through CLI, Java, or batch | A fresh dedicated JVM for each job. | The calling entry point's budget profile still applies. |

A dedicated guest follows ordinary JVM lifetime: non-daemon application threads
can keep it running after `main` returns. A reusable guest finishes a job when its
entry point returns, interrupts lingering job threads, and treats unexpected guest
termination, including `System.exit(0)`, as `guest_exit` unless an earlier reason
takes precedence. The reusable harness resets selected process state, not every
**JDK** global setting or filesystem effect. One completed job per worker avoids
reuse between executed jobs; it does not provide host isolation. See the
[batch lifecycle contract](reference/batch.md) for recycling and state boundaries.

Single-stack reusable guests also have an
[uncaught-exception recognition limitation](reference/output.md#uncaught-exceptions):
exceptions thrown inside JDK or precompiled dependency code can leave completion
metadata indicating success while their stack trace appears in guest stderr.
This limitation does not change the dedicated CLI envelope's exception reporting.

## Limits

| Option | Meaning |
| --- | --- |
| `--timeout-ms` | Monotonic elapsed deadline beginning at the trace phase. Dedicated launch is included; source preparation and reusable guest startup are excluded. The runner must bound the whole job. |
| `--max-snapshots` | Number of complete snapshots captured, including snapshots subsequently replaced in selected-breakpoint mode. The next attempted snapshot stops the job. |
| `--max-output-bytes` | Retained guest bytes **per stream**, independently for stdout and stderr. Excess bytes trigger a stop; retention is capped before copying them. |
| `--max-heap-objects` | Distinct reachable object identities per snapshot. |
| `--max-elements` | Cumulative inspected array slots, fields, stack frames/locals, and string backing-storage units per snapshot. Repeated inspection can count more than once. |
| `--max-trace-bytes` | Accounted retained snapshot storage, including the snapshot being built. See accounting below. |
| `--max-source-bytes` | Submitted UTF-8 source bytes. The CLI checks raw input before decoding and parsing; Java and batch jobs check the decoded source string's UTF-8 size. |
| `--max-threads` | Live application threads in multithread capture. |
| `--max-frames` | Total frames in multithread mode before filtering application stacks, checked before fetching frames. |
| `--max-snapshot-bytes` | Accounted bytes for one snapshot, using the same accounting as trace bytes. |
| `--max-source-files` | Number of delimiter-defined source files, checked before parsing. Undelimited input counts as one file. |

Counted usage equal to a cap is allowed; an attempt to exceed it stops the job.
A nonzero deadline instead expires when elapsed tracing time reaches its configured
duration. The first observed stop reason wins; concurrent deadline/output/cancellation
events do not have a guaranteed ordering. To turn a cap off explicitly, set it to zero.

`traceBytes` is deterministic accounting, not a measurement of Java heap use. Each
snapshot starts at 256 units; encountered objects charge 128 units; inspected
items charge 64 units; copied output charges its byte length. At commit, the
snapshot charge is at least three times the character count of its internal JSON
representation. The previous snapshot remains charged while a replacement is
built. This bounds retained data under the selected accounting model. It does
not cover all compiler, parser, JDI, serializer, JVM, or source-text allocations.
Only the runner can enforce an actual process/container memory ceiling.

Opt-in primitive binary metadata is derived during serialization, after snapshot
admission. It expands response JSON and serialization allocations without changing
retained snapshot accounting. Neither `traceBytes`/`snapshotBytes` nor guest
`outputBytes` is a final serialized-response byte limit. See the
[binary metadata contract](reference/output.md#optional-primitive-binary-metadata).
Its [encoding definitions and references](reference/output.md#encoding-definitions-and-references)
identify the normative specifications and informative explanations for the value representations.

## Inspection policies

For why the policies exist, their trade-offs, and when to choose each, see the
[inspection policies explanation](explanation/inspection-policies.md).

`TRUSTED` retains existing specialized collection, map, wrapper, and stream
inspection. It can invoke guest methods (`toArray`, `entrySet`, entry accessors,
primitive-wrapper getters, lazy enum `hashCode`, a color `getRGB` fallback, and
`flush`). Overrides can mutate state or hang.
The independent watchdog can terminate the guest even while such a call blocks.
This includes set wrappers: an unmodifiable set's inherited `toArray()` can
delegate to arbitrary backing code. Element limits apply after that call returns
its array and cannot prevent the guest allocation. Guest exceptions and null
array results use the inspected object's normal fallback; budget stops still
discard the incomplete snapshot and preserve previously admitted snapshots.
See [set objects](reference/output.md#set-objects).
Trusted inspection also exposes selected stored components of supported
[`java.time` values](reference/output.md#date-and-time-objects) without guest calls.
Those fields and reached component objects use the ordinary element and heap
budgets. Field-only inspection retains raw traversal for these objects.

`FIELDS` avoids these method invocations. Objects, including collections and
wrappers, are inspected through fields; arrays, strings, and colors retain dedicated
representations. Colors are read from fields without invoking `getRGB`.
Collection internals vary by JDK and are not promised to match
TRUSTED collection presentation. A diagnostic describes this policy in the result.
Only bytes already emitted to the OS pipes can be captured: guest buffers are not
flushed by calling into the program.

FIELDS accepts only the submitted source bundle for source discovery. Include all
required source files in the delimited stream; it does not discover neighboring
Java source files. Standard JDK classes and explicitly configured binary
dependencies remain available to compilation and execution. The
policy restricts inspection, not what guest code itself may do.

## Envelope

CLI `trace --result-envelope`, Java `TraceOutcome.json()`, and the `result` object
in a batch response share this version 1 schema. Entry-point defaults and guest
lifetimes remain [caller-specific](#guest-modes). The result contains:

- `schemaVersion`: currently `1`.
- `format`: `pytutor` or `modern`; batch request parse failures use `unknown`.
- `status`: `completed`, `stopped`, or `failed`.
- `stopReason`: null on success, otherwise a stable code below.
- `phase`: usually `source`, `compile`, `trace`, or `serialize` at
  completion/failure. Batch request parsing can report `parse`; Java and batch
  configuration or guest-startup failures can report `validation` or `tracer`.
- `complete`: true when the tracer recorded completion without a stop or failure.
  It does not establish successful guest behavior; the reusable-guest exception
  limitation described above can leave it true after an uncaught exception.
- `trace`: a format-specific sequence root, or null if no usable payload was
  available. Modern envelope payloads preserve optional null properties that
  ordinary modern CLI output omits; see the [serialization rules](reference/output.md#null-properties-in-modern-output).
- `limits`: effective budgets, using the camelCase names from `TraceLimits`.
- `counters`: available accounting, including captured/retained snapshots,
  accounted bytes, dropped unfinished snapshots, elapsed tracing milliseconds,
  retained stream byte counts, and the guest exit code when available.
- `diagnostics`: explanatory text, including compiler failures or guest exception
  types. Diagnostic strings are not stable identifiers.
- `stdout` and `stderr`: bounded guest output decoded as UTF-8, with replacement
  for incomplete/invalid sequences. Available even if no snapshot completed.

Envelope traces always use a root containing a sequence (`trace` for Python Tutor,
`steps` for modern). Selected-breakpoint jobs retain the latest snapshot per
`(file, line)` unless accumulation is requested; retained states appear in capture
order. Existing non-envelope selected-breakpoint dictionary output still groups
by bare line number, so one key can contain hits from multiple files. See
[result contracts](reference/output.md#result-contracts).

### Counter fields

The `counters` object contains integer measurements available when the result is
constructed. A **session result** in the table below includes a source or compile
failure handled inside an established tracing session, even when tracing never
began. Such a result includes the five core counters, with zero values before any
capture or trace-phase work.

| Key | Meaning | Presence |
| --- | --- | --- |
| `snapshotsCaptured` | Number of committed snapshots, including snapshots later replaced in selected-location mode. | Every session result. |
| `snapshotsRetained` | Number of committed snapshots remaining in the retained history. | Every session result. |
| `retainedBytes` | Accounted storage for retained snapshots; not process memory or serialized-response size. | Every session result. |
| `droppedSnapshots` | Zero or one: whether an unfinished snapshot was discarded. Replaced committed snapshots do not increment it. | Every session result. |
| `elapsedMillis` | Elapsed monotonic milliseconds from entering the trace phase until result construction, including later rendering; zero if that phase was never entered. Excludes source preparation and reusable-guest startup. | Every session result. |
| `stdoutBytes` | Retained guest stdout bytes, before UTF-8 decoding and replacement of incomplete sequences. | When byte accounting is available for stdout; zero means an observed empty stream. |
| `stderrBytes` | Retained guest stderr bytes, measured independently of stdout. | When byte accounting is available for stderr; zero means an observed empty stream. |
| `guestExitCode` | Exit status of the guest process attached to this job's tracing session. | Only when that attached process has terminated by result construction. |

Results produced before a tracing session is established use `counters: {}`.
These include batch parse and JSON-nesting failures, Java/batch configuration
rejection or guest-startup failure, and interrupted batch-worker admission.
Session results without stream byte accounting omit the corresponding byte keys.
Consumers must accept missing counters rather than treating absence as a measured
zero or using it to prove that no guest started.

A healthy reusable guest normally remains alive when its job result is created,
so `guestExitCode` is absent. A recorded exit code can also reflect termination
requested by the tracer after a timeout, cancellation, or another stop. Exact
values depend on the platform and termination path; they do not identify the
cause independently of `status`, `stopReason`, and diagnostics. A stopped result
need not include this counter if termination has not completed yet.

### Reported phases

`phase` records the last phase assigned by the calling entry point. The same
logical outcome can therefore report different phases:

| Outcome | CLI `trace --result-envelope` | Java `Tracer.trace` and batch results |
| --- | --- | --- |
| Normal completion with rendered JSON | `trace` | `serialize` |
| `source_file_limit` while admitting submitted files | `compile` | `source` |

The CLI enters `compile` before checking the submitted file count, while Java and
batch check it in `source`. For successful rendering, Java and batch advance to
`serialize`; the CLI retains `trace`. Thus `phase: "compile"` can describe a stop
before compiler work, and a completed CLI result with `phase: "trace"` still
contains rendered JSON. Interpret the phase together with status and stop reason;
it is not a common sequence of milestones across callers.

### Trace payload

Payload availability depends on the caller and completion path, not final status
alone:

| Caller and completion path | Payload when no snapshot was committed |
| --- | --- |
| CLI envelope before a guest is attached, such as source or compile failure | Null. |
| CLI envelope after a guest is attached | A format-specific root with an empty sequence, unless rendering fails. |
| Java or batch capture returns normally and empty history is rendered | A format-specific root with an empty sequence, even if a later guest-liveness check records `guest_exit`. |
| Java or batch recovers from a thrown preparation, capture, or rendering failure | Null; recovery requires nonempty retained history before rendering a partial payload. |
| Java or batch rejects configuration or cannot start its guest | Null. |

Serialization failure can also leave a null payload after capture. A worker can
record `guest_exit` after rendering, so even a `failed` result can contain an empty
sequence. Null therefore does not prove that no guest ran, and an empty sequence
does not prove successful guest execution. Inspect `status`, `stopReason`, and
available snapshot counters instead of inferring progress from null versus an
empty sequence. Completed
snapshots are committed atomically; an unfinished snapshot is discarded while
previously committed snapshots remain available when rendering succeeds.

Snapshot output is also caller-specific. CLI envelopes refresh trailing guest
output only on the final retained snapshot overall, while Java/batch selected
payloads refresh the latest retained hit at each `(file, line)` unless a
`trace_limit` stop prevents that projection. Ordinary CLI selected output instead
refreshes the final hit under each bare line-number key, which can combine hits
from multiple files. Envelope
`stdout` and `stderr` carry the available streams independently of that projection.
See [input and output observations](reference/output.md#input-and-output-observations).

## Stop codes

The table lists the emitted codes and result statuses. Here **CLI** means a result
from `trace --result-envelope`; Java means `Tracer.trace`; batch includes
`batch-trace` and `BatchTracer`. Ordinary CLI tracing can report a controlled stop
on stderr without an envelope. Thread-only entries require multithread capture.

| Code | Status | Entry points | Meaning and configuration |
| --- | --- | --- | --- |
| `timeout` | `stopped` | CLI, Java, batch | Trace-phase deadline reached; `--timeout-ms` / `timeoutMillis`. |
| `cancelled` | `stopped` | CLI, Java, batch | Tracing owner interrupted or internal cancellation requested. |
| `snapshot_limit` | `stopped` | CLI, Java, batch | Completed-capture cap; `--max-snapshots` / `snapshots`. |
| `output_limit` | `stopped` | CLI, Java, batch | Per-stream retained output cap; `--max-output-bytes` / `outputBytes`. |
| `heap_limit` | `stopped` | CLI, Java, batch | Reachable objects per snapshot; `--max-heap-objects` / `heapObjects`. |
| `element_limit` | `stopped` | CLI, Java, batch | Inspected elements per snapshot; `--max-elements` / `elements`. |
| `trace_limit` | `stopped` | CLI, Java, batch | Accounted snapshot storage; `--max-trace-bytes` / `traceBytes`. |
| `snapshot_byte_limit` | `stopped` | CLI, Java, batch | Accounted storage per snapshot in every capture mode; `--max-snapshot-bytes` / `snapshotBytes`. |
| `source_limit` | `stopped` | CLI, Java, batch | Submitted source bytes; `--max-source-bytes` / `sourceBytes`. |
| `source_file_limit` | `stopped` | CLI, Java, batch | Delimiter-defined source files; `--max-source-files` / `sourceFiles`. |
| `thread_limit` | `stopped` | CLI, Java, batch; thread-only | Live application threads; `--max-threads` / `threads`. |
| `frame_limit` | `stopped` | CLI, Java, batch; thread-only | Total frames before application filtering; `--max-frames` / `frames`. |
| `unsupported_virtual_thread` | `stopped` | CLI, Java, batch; thread-only | Virtual-thread capture is unsupported; no budget removes this check. |
| `value_nesting_limit` | `stopped` | CLI, Java, batch | Fixed inline-value nesting ceiling; see [representation nesting](#representation-nesting). |
| `inline_value_cycle` | `stopped` | CLI, Java, batch | Cyclic inline value graph; ordinary guest heap-reference cycles remain supported. |
| `type_nesting_limit` | `stopped` | CLI, Java, batch | Fixed type-helper safety boundary; see [representation nesting](#representation-nesting). |
| `json_nesting_limit` | `stopped` | Batch | Request JSON nesting exceeds the fixed parse ceiling; the correlation ID is null. |
| `compile_error` | `failed` | CLI, Java, batch | Source read, parse, or compilation failure, or dependency preparation failure such as a missing class-path entry or invalid JAR. Consult `phase` and diagnostics. |
| `guest_exception` | `failed` | CLI, Java, batch | Observed uncaught guest exception, subject to the [reusable-guest recognition limitation](reference/output.md#uncaught-exceptions). |
| `guest_exit` | `failed` | CLI, Java, batch | Nonzero dedicated-guest exit, or unexpected reusable-guest termination including exit zero, without an earlier recorded reason. |
| `tracer_error` | `failed` | CLI, Java, batch | Tracing, guest startup, or serialization failure; also malformed Java/batch breakpoint text parsed after successful compilation in phase `trace`. |
| `parse_error` | `failed` | Batch | Request decoding failure, including negative limits rejected while constructing the decoded request; the correlation ID is null. |
| `validation_error` | `failed` | Java, batch | Configuration rejected during job validation, such as an incompatible format/capture combination. Deferred breakpoint syntax errors instead use `tracer_error`. |
| `interrupted` | `stopped` | Batch | Worker admission interrupted before execution. |

With `trace --result-envelope`, exit codes are 0 for completed jobs, 1 for failed
jobs, 2 for invalid CLI configuration, and 3 for controlled stops, including fixed
representation checks. Invalid CLI configuration follows Picocli's stderr diagnostics
and need not produce an envelope. Environment preflight and unreadable `--stdin-file`
input can fail before the envelope session begins, returning 1 with no JSON; the
latter currently prints a stack trace to stderr. Ordinary tracing also uses 1 for
trace-generation failures, 2 for
invalid configuration, and 3 for controlled stops, but retains its legacy output
behavior: a captured guest exception can produce trace JSON with exit code 0.
Use a CLI envelope for observed guest-failure metadata rather than relying only
on ordinary trace-generation success. The [guest-mode limitation](#guest-modes)
still applies to single-stack Java and batch results. Guest stdout/stderr are data
inside the envelope; tracer diagnostics must not be mixed into JSON stdout.
The `batch-trace` process reports stream-processing success independently of each
job's result; inspect every response as described in the [batch protocol](reference/batch.md).
The [CLI exit-status lookup](reference/cli.md#exit-statuses) summarizes process
statuses and cases where no envelope is produced.

Library callers use `Tracer.trace(TraceRequest)` with `TraceLimits` and inspect the
returned `TraceOutcome`; see [Java integration](reference/java-api.md). The supported
interface does not expose a session handle or an explicit cancellation method.
Internally, `TraceSession` owns deadlines, cancellation, and retention. Its binding
is confined to the owner thread and is not inherited by child threads. Session
cancellation and owner-thread interruption are implementation lifecycle controls.

The runner must not rely on an envelope after forced termination, JVM exhaustion,
a fatal JVM error, or host failure. A kill may interrupt JSON writing. Reject
incomplete artifacts and publish an authoritative runner failure result instead.
Recovering checkpoints after a kill is deferred.

## Multithread capture

Thread capture adds `thread_limit`, `frame_limit`, and `unsupported_virtual_thread`.
The separate `snapshot_byte_limit` applies to every capture mode. Batch fields
`threads` and `frames` apply to multithread capture, while `snapshotBytes` applies
to every job. Omitted batch limits or zero are unlimited. See the
[stop-code table](#stop-codes) for statuses and matching options.

Multithread capture always disables guest method invocation, including stream
flush and collection accessors, even when the selected inspection policy is
`TRUSTED`. This avoids running one thread while observing other threads' stacks.

## Representation nesting

Unlimited resource budgets do not disable fixed representation safety checks.
Inline snapshot paths longer than 32 values stop with `value_nesting_limit`;
inline identity cycles stop with `inline_value_cycle`. The rejected snapshot is
not retained. Guest heap references are leaves for this check, so long reference
chains and ordinary cyclic guest objects remain supported. Type helper depth
rejections use `type_nesting_limit`. See [nesting boundaries](contributing/security-analysis.md#nesting-boundaries)
for the exact checks and remaining source-parser isolation requirements.

## Verification

Contributor commands for checking and intentionally regenerating committed trace
fixtures are maintained in the [development guide](contributing/development.md#compatibility-fixtures).
Verification preserves source locations, event order, output, and reference
relationships; changing expected fixtures is not a substitute for diagnosing a regression.
