# Bounded tracing and result schema v1

Ordinary instructor invocations use finite budgets while preserving successful
Python Tutor and modern JSON shapes. A limit stop writes no JSON to stdout, prints
its reason to stderr, and exits with code 3. Use `--result-envelope` for explicit
job status and completed partial snapshots. These settings do not isolate a program from the host.

Ordinary defaults are 10 seconds of tracing, 10,000 snapshots, 1 MiB of output per
stream, 10,000 heap objects and 100,000 elements per snapshot, 64 MiB of accounted
trace data, 1 MiB of submitted source, 128 streamed source files, 64 live
application threads, 4,096 total frames per snapshot, and 8 MiB per snapshot. These are
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
For compatibility, envelope mode retains its existing policy: omitted limits are
unlimited, and callers should supply their intended budgets explicitly.
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
hosted job. In envelope mode, omitted budget options use zero (unlimited).
Negative values, timeouts greater than `Long.MAX_VALUE / 1_000_000` milliseconds,
and unsupported options are rejected before tracing.

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

Usage equal to a cap is allowed. A job stops when it would exceed a cap. The first
observed stop reason wins; concurrent deadline/output/cancellation events do not
have a guaranteed ordering. To turn a cap off explicitly, set it to zero.

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

`TRUSTED` retains existing specialized collection, map, wrapper, and stream
inspection. It can invoke guest methods (`toArray`, `entrySet`, entry accessors,
primitive-wrapper getters, lazy enum `hashCode`, a color `getRGB` fallback, and
`flush`). Overrides can mutate state or hang.
The independent watchdog can terminate the guest even while such a call blocks.

`FIELDS` avoids these method invocations. Objects, including collections and
wrappers, are inspected through fields; arrays, strings, and colors retain dedicated
representations. Colors are read from fields without invoking `getRGB`.
Collection internals vary by **JDK** and are not promised to match
TRUSTED collection presentation. A diagnostic describes this policy in the result.
Only bytes already emitted to the OS pipes can be captured: guest buffers are not
flushed by calling into the program.

FIELDS accepts only the submitted source bundle for source discovery. Include all
required source files in the delimited stream; it does not discover neighboring
Java source files. Standard JDK classes and explicitly configured binary
dependencies remain available to compilation and execution. The
policy restricts inspection, not what guest code itself may do.

## Envelope

The opt-in result contains:

- `schemaVersion`: currently `1`.
- `format`: `pytutor` or `modern`; batch request parse failures use `unknown`.
- `status`: `completed`, `stopped`, or `failed`.
- `stopReason`: null on success, otherwise a stable code below.
- `phase`: usually `source`, `compile`, `trace`, or `serialize` at
  completion/failure. Batch request parsing can report `parse`; Java and batch
  configuration or guest-startup failures can report `validation` or `tracer`.
- `complete`: true only for normal execution and capture completion.
- `trace`: a normal format-specific root, or null if no usable payload was available.
- `limits`: effective budgets, using the camelCase names from `TraceLimits`.
- `counters`: available accounting, including captured/retained snapshots,
  accounted bytes, dropped unfinished snapshots, elapsed tracing milliseconds,
  retained stream byte counts, and the guest exit code when available.
- `diagnostics`: explanatory text, including compiler failures or guest exception
  types. Diagnostic strings are not stable identifiers.
- `stdout` and `stderr`: bounded guest output decoded as UTF-8, with replacement
  for incomplete/invalid sequences. Available even if no snapshot completed.

Envelope traces always use a root containing a sequence (`trace` for Python Tutor,
`steps` for modern). Selected-breakpoint jobs retain the latest snapshot per line
unless accumulation is requested; retained states appear in capture order. Existing
non-envelope selected-breakpoint dictionary output is unchanged.

An empty sequence means capture ran without a completed snapshot. Null means
no usable trace payload was available, for example after compilation failure,
serializer failure, or a failed Java/batch capture that completed no snapshots.
It does not prove that no guest was launched. Completed snapshots are committed
atomically; an interrupted snapshot is discarded rather than returned with
dangling references.

Stop codes:

| Code | Meaning |
| --- | --- |
| `timeout`, `cancelled` | Deadline or caller cancellation. |
| `snapshot_limit`, `output_limit`, `heap_limit`, `element_limit`, `trace_limit` | Tracing resource cap reached. |
| `source_limit`, `source_file_limit` | Submission input cap reached. |
| `compile_error` | Source read/parse/compile failure. Consult `phase` and diagnostics. |
| `guest_exception` | Observed uncaught exception in the guest. |
| `guest_exit` | Nonzero dedicated-guest exit, or unexpected reusable-guest termination, without an earlier recorded stop/failure. |
| `tracer_error` | Recoverable internal tracing or serialization failure. |
| `parse_error`, `validation_error` | Batch request parsing failure, or invalid Java/batch configuration. |
| `interrupted` | Batch worker admission interrupted before execution. |

With `trace --result-envelope`, exit codes are 0 for completed jobs, 1 for failed
jobs, 2 for invalid CLI configuration, and 3 for controlled stops. Invalid CLI
configuration follows Picocli's stderr diagnostics and need not produce an
envelope. Ordinary tracing also uses 1 for trace-generation failures, 2 for
invalid configuration, and 3 for controlled stops, but retains its legacy output
behavior: a captured guest exception can produce trace JSON with exit code 0.
Use an envelope when callers must distinguish successful guest execution from
successful trace generation. Guest stdout/stderr are data inside the envelope;
tracer diagnostics must not be mixed into JSON stdout.
The `batch-trace` process reports stream-processing success independently of each
job's result; inspect every response as described in the [batch protocol](reference/batch.md).

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

## Verification

```sh
./mvnw -B -ntp clean package
./scripts/uv run python -m unittest discover -s examples -p test_verify.py
./scripts/uv run python examples/verify.py
```

The committed regression fixtures live in `examples/regression/`; their manifest
selects the source programs and capture options. **Git** history records fixture and
source-line updates. Verification never overwrites fixtures. Regeneration is explicit:

```sh
./scripts/uv run python examples/verify.py --update
```

Review every regenerated diff. The normalizer changes heap IDs only, preserving
reference sharing, cycles, values, source lines, step order, and output metadata.
The existing `examples/test.sh FILE [OPTIONS...]` and `generate_all.sh` commands
remain output-generation tools rather than regression verification commands.

Thread capture adds `thread_limit`, `frame_limit`, `snapshot_byte_limit`, and
`unsupported_virtual_thread` stop reasons. An unfinished snapshot is discarded;
previously committed snapshots remain available in the envelope. Batch limit
fields are `threads`, `frames`, and `snapshotBytes`; like other envelope limits,
omitted fields or zero are unlimited.

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
