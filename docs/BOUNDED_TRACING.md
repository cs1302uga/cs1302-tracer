# Bounded tracing and result schema v1

Ordinary instructor invocations now use finite budgets while preserving successful
Python Tutor and modern JSON shapes. A limit stop writes no JSON to stdout, prints
its reason to stderr, and exits with code 3. Use `--result-envelope` for explicit
job status and completed partial snapshots. These settings do not isolate a program from the host.

Ordinary defaults are 10 seconds of tracing, 10,000 snapshots, 1 MiB of output per
stream, 10,000 heap objects and 100,000 elements per snapshot, 64 MiB of accounted
trace data, 1 MiB each of submitted source and guest input, and 128 streamed source files. These are
configurable interactive-workload defaults, not measured process-memory ceilings.
Source limits apply independently to the submitted bundle and to the combined
compiled dependencies read for metadata. Discovery follows compiler-emitted source
identities instead of scanning every neighboring Java file. Discovery failures
report the `compile` phase; unreferenced files are not parsed. These limits do not
bound javac's own dependency reading or allocations.
The tracing deadline excludes source reading, parsing, and compilation.

Use `--unlimited` to disable these defaults. An explicit individual limit still
applies with `--unlimited`, regardless of option order; zero disables that limit.
For compatibility, envelope mode retains its existing policy: omitted limits are
unlimited, and callers should supply their intended budgets explicitly.
`--inspection FIELDS` still requires `--result-envelope`.
Hosted execution requires the separate [runner contract](RUNNER_CONTRACT.md).

```sh
java -jar target/code-tracer-jar-with-dependencies.jar trace \
  --result-envelope --inspection FIELDS -a -f pytutor \
  --timeout-ms 5000 --max-snapshots 1000 --max-output-bytes 65536 \
  --max-heap-objects 1000 --max-elements 10000 --max-trace-bytes 33554432 \
  --max-source-bytes 262144 --max-source-files 32 --max-input-bytes 1048576 < submission.txt
```

This is an illustrative teaching-workload profile, not a security guarantee or a
universal production default. Tune it for your examples and the runner's total
memory, concurrency, and wall-clock budgets. Supply finite budgets for every
hosted job. In envelope mode, omitted budget options use zero (unlimited).
Negative values and unsupported options are rejected before tracing.

A local JDK 25 trial on September 12, 2026 ran all 11 regression workloads as
self-contained bundles under this profile. Ten completed normally; the intentional
exception example returned `guest_exception`. Observed maxima were 323 ms of
tracing, 8 retained snapshots, 9,870 accounted trace bytes, 1,128 source bytes,
52 stdout bytes, and 188 stderr bytes. These small-workload measurements informed
the generous example profile; they exclude compilation time and are not a capacity
benchmark for a concurrent hosted service.

## Limits

| Option | Meaning |
| --- | --- |
| `--timeout-ms` | Monotonic elapsed deadline beginning before breakpoint discovery/guest launch. Compilation and input reading are excluded; the runner must bound the whole job. |
| `--max-snapshots` | Number of complete snapshots captured, including snapshots subsequently replaced in selected-breakpoint mode. The next attempted snapshot stops the job. |
| `--max-output-bytes` | Retained guest bytes **per stream**, independently for stdout and stderr. Excess bytes trigger a stop; retention is capped before copying them. |
| `--max-heap-objects` | Distinct reachable object identities per snapshot. |
| `--max-elements` | Cumulative inspected array slots, fields, stack frames/locals, and string backing-storage units per snapshot. Repeated inspection can count more than once. |
| `--max-trace-bytes` | Accounted retained snapshot storage, including the snapshot being built. See accounting below. |
| `--max-source-bytes` | Raw source bytes before UTF-8 decoding and parsing. |
| `--max-input-bytes` | UTF-8 guest input bytes; file input is checked before retaining each chunk. |
| `--max-source-files` | Number of delimiter-defined source files, checked before parsing. Undelimited input counts as one file. |

Usage equal to a cap is allowed. A job stops when it would exceed a cap. The first
observed stop reason wins; concurrent deadline/output/cancellation events do not
have a guaranteed ordering. To turn a cap off explicitly, set it to zero.

`traceBytes` is deterministic accounting, not a measurement of Java heap use. Each
snapshot starts at 256 units; encountered objects charge 128 units; inspected
items charge 64 units; cumulative output charges its logical byte length even
when stored in shared buffers. At commit, the
snapshot charge is at least three times the character count of its internal JSON
representation. The previous snapshot remains charged while a replacement is
built. This bounds retained data under the selected accounting model. It does
not cover all compiler, parser, JDI, serializer, JVM, or source-text allocations.
Only the runner can enforce an actual process/container memory ceiling.

## Inspection policies

`TRUSTED` retains existing specialized collection, map, wrapper, and stream
inspection. It can invoke guest methods (`toArray`, `entrySet`, entry accessors,
primitive-wrapper getters, and `flush`). Overrides can mutate state or hang.
The independent watchdog can terminate the guest even while such a call blocks.

`FIELDS` avoids these method invocations. Objects, including collections and
wrappers, are inspected through fields; arrays and strings retain dedicated
representations. Collection internals vary by JDK and are not promised to match
TRUSTED collection presentation. A diagnostic describes this policy in the result.
Only bytes already emitted to the OS pipes can be captured: guest buffers are not
flushed by calling into the program.

FIELDS accepts only the submitted source bundle for source discovery. Include all
required files in the delimited stream; it does not search neighboring directories
for dependencies. Standard JDK classes remain available to the compiler. The
policy restricts inspection, not what guest code itself may do.

## Envelope

The opt-in result contains:

- `schemaVersion`: currently `1`.
- `format`: `pytutor` or `modern`.
- `status`: `completed`, `stopped`, or `failed`.
- `stopReason`: null on success, otherwise a stable code below.
- `phase`: `source`, `compile`, `trace`, or `serialize` at completion/failure.
- `complete`: true only for normal execution and capture completion.
- `trace`: a normal format-specific root, or null if capture was unavailable.
- `limits`: effective budgets, using the camelCase names from `TraceLimits`.
- `counters`: captured/retained snapshots, accounted bytes, dropped unfinished
  snapshots, elapsed tracing milliseconds, retained stream byte counts, and the
  guest exit code when available.
- `diagnostics`: explanatory text, including compiler failures or guest exception
  types. Diagnostic strings are not stable identifiers.
- `stdout` and `stderr`: bounded guest output decoded as UTF-8, with replacement
  for incomplete/invalid sequences. Available even if no snapshot completed.

Envelope traces always use a root containing a sequence (`trace` for Python Tutor,
`steps` for modern). Selected-breakpoint jobs retain the latest snapshot per line
unless accumulation is requested; retained states appear in capture order. Existing
non-envelope selected-breakpoint dictionary output is unchanged.

Use repeatable `--breakpoint-at path/to/File.java:LINE` selectors with
`--result-envelope` to retain the latest state independently for each exact source
location. Paths match the source-relative identities reported by `list-breakpoints
--json`; basename-only guessing is not performed. Separators and redundant `.`
segments are normalized. Absolute paths, parent traversal, and nonpositive lines
are rejected. Qualified selectors cannot be mixed with numeric `--breakpoint`.

With qualified selectors, `--accumulate-breakpoints` retains every hit and `-a`
returns hits in chronological order. Qualified chronological mode does not add an
implicit main-exit snapshot; uncaught-exception reporting remains enabled. Duplicate
selectors do not create duplicate requests. Both payload formats retain their
existing envelope sequence shape and source-file metadata. Numeric selectors keep
their existing cross-file latest-per-line behavior.

Invalid selector syntax/combinations exit 2 before source reading. A syntactically
valid selector that does not name a compiler-reported executable location produces
a failed `compile`-phase envelope (`compile_error`, exit 1) before guest launch.

An empty sequence means a guest was launched but no snapshot completed. Null means
no usable trace payload was available, for example after compilation failure or
serializer failure. Completed snapshots are committed atomically; an interrupted
snapshot is discarded rather than returned with dangling references.

Stop codes:

| Code | Meaning |
| --- | --- |
| `timeout`, `cancelled` | Deadline or caller cancellation. |
| `snapshot_limit`, `output_limit`, `heap_limit`, `element_limit`, `trace_limit` | Tracing resource cap reached. |
| `source_limit`, `source_file_limit`, `input_limit` | Source or guest-input cap reached. |
| `compile_error` | Source read/parse/compile failure. Consult `phase` and diagnostics. |
| `guest_exception` | Observed uncaught exception in the guest. |
| `guest_exit` | Nonzero guest exit without an earlier recorded stop/failure. |
| `tracer_error` | Recoverable internal tracing or serialization failure. |

CLI exit codes are 0 for completed jobs, 1 for failed jobs, 2 for invalid CLI
configuration, and 3 for controlled stops. Invalid CLI configuration follows
Picocli's stderr diagnostics and need not produce an envelope. Guest stdout/stderr
are data inside the envelope; tracer diagnostics must not be mixed into JSON stdout.

Library callers can bind a `TraceSession` with try-with-resources, set phase to
`trace` before launching, and call `cancel()` from another thread. Owner-thread
interruption also requests cancellation. Sessions are not nested or inherited by
child threads. The CLI demonstrates the full compile/capture/result lifecycle.

The runner must not rely on an envelope after forced termination, JVM exhaustion,
a fatal JVM error, or host failure. A kill may interrupt JSON writing. Reject
incomplete artifacts and publish an authoritative runner failure result instead.
Recovering checkpoints after a kill is deferred.

## Verification

```sh
./mvnw clean package
python3 -m unittest discover -s examples -p test_verify.py
python3 examples/verify.py
```

The committed regression fixtures were generated from `main` at `bd7626f` using
JDK 25. Verification never overwrites fixtures. Regeneration is explicit:

```sh
python3 examples/verify.py --update
```

Review every regenerated diff. The normalizer changes heap IDs only, preserving
reference sharing, cycles, values, source lines, step order, and output metadata.
The existing `examples/test.sh FILE [OPTIONS...]` and `generate_all.sh` commands
remain output-generation tools rather than regression verification commands.

Guest input is fed concurrently with execution and closed to deliver EOF. The
session terminates the guest before joining a blocked feeder during cleanup.
`inputBytes` is an additive budget field in envelope schema v1; `input_limit` is a
controlled stop (exit 3), reported in the `source` phase before compilation.
`--stdin` counts UTF-8 bytes too; its argument string is already allocated by CLI
parsing. The legacy eight-argument `TraceLimits` constructor leaves input unlimited.

Chronological duplicate terminal states count as captured work but are omitted
from retained output in both ordinary and envelope mode. Final output refresh
uses the same retention accounting in both paths.

Internal captures share append-only output histories and snapshot prefix lengths.
The CLI materializes cumulative output as each step is serialized. Public library
trace methods still return independent cumulative arrays. These storage changes
do not change logical budgets or the cumulative wire schema.

CLI serialization requires writable temporary storage for a JSON spool, which is
deleted on success or failure. JSON is published only after serialization completes;
ordinary mode also checks cancellation/limits before publication. Temporary disk
usage can grow with the cumulative wire output. See [performance and storage](PERFORMANCE.md)
for measured savings and the distinction between logical accounting and actual memory.
