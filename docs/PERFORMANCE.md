# Trace storage and performance

Source/type/lambda/local-final indexes are prepared once per trace. Runtime
object identities and type inference remain snapshot-local. Metadata discovery
follows compiled classes' SourceFile attributes rather than scanning unrelated
Java files under the source root.

## Output ownership and compatibility

The internal `Snapshot` interface separates captured state from public
`ExecutionSnapshot` records. `OutputStorage` keeps append-only stdout/stderr
histories; each captured state pins a prefix length. Growth preserves older
prefixes. If cumulative bytes shrink or change, such as after stderr banner
sanitization, a separate history preserves previously captured output.

Existing `DebugTraceHelper.trace`, `traceLatest`, and `traceChronological` methods
still return public records with independent cumulative byte arrays and mutable
result lists. The record retains its eight components and constructors. These
eager library results intentionally retain cumulative storage costs. The CLI
uses `capture` and `captureChronological` internally, keeping compact states
through selection, replacement, suppression, and final-output refresh.

`Snapshot.metadata()` is only the non-output state; use `materialize()` for a full
public record. Materializing a compact capture creates fresh output arrays, so
mutating one result cannot affect other snapshots or future materializations.

Chronological and accumulating serializer models generate steps on demand.
Modern latest-per-breakpoint output still constructs one step model per selected
line; this is not a claim that every serializer allocation is constant-space.
Both formats preserve their existing cumulative output schema, including the
existing decoding behavior for incomplete UTF-8 at a snapshot boundary.

CLI JSON is serialized to a temporary file, then published after serialization
succeeds. Ordinary output also performs its final cancellation/limit check before
publication. Envelope serialization failures replace the spool with a failed
result before publication. The spool is deleted on success or failure. This avoids
holding the entire JSON document in Java memory, but requires temporary disk
space proportional to the serialized document. Publication to stdout itself is
not transactional: an external write failure can still interrupt delivery.

Logical `traceBytes` accounting is unchanged. It charges the same cumulative
snapshot representation, including a replacement while its predecessor is still
retained. Physical sharing does not increase the amount of trace admitted by a
budget. Initial capture, accounting, and public materialization still create
transient cumulative arrays; this change removes retained copies, not all copying
or quadratic wire-output growth. It does not bound the entire JVM heap.

## Reproducing measurements

Build with `./mvnw clean package`. For a fresh-process benchmark:

```sh
python3 scripts/benchmark.py --jar target/code-tracer-jar-with-dependencies.jar --runs 3
```

Run compared artifacts sequentially on an otherwise idle machine. The script
records the artifact hash, JDK, platform, elapsed time, process-tree peak RSS,
budget counters, and normalized trace hashes. RSS is not Java heap usage.

For retained output storage:

```sh
python3 scripts/profile_output.py --jdk "$(jenv prefix)" --jar PATH_TO_JAR
python3 scripts/profile_output.py --jdk "$(jenv prefix)" --jar PATH_TO_JAR --compact
```

The standalone instrumentation agent counts distinct reachable output arrays by
identity and measures their shallow JVM sizes. Legacy mode measures the public
eager result. Compact mode measures backing-buffer capacity, including unused
space and empty metadata arrays, and separately counts capture/history objects.
It uses reflection with `--add-opens=java.base/java.io=ALL-UNNAMED` solely for the
measurement harness. Each workload runs in a fresh JVM with a 512 MiB heap. The
agent validates output contents, monotonic lengths, and final length. Library
tracing deliberately runs without CLI caps so larger cases can expose growth.

This measurement excludes non-output state, compiler/parser/JDI state, transient
allocations, and serializer models. Common snapshot metadata records are excluded
in both modes. It is not a whole-heap dominator analysis or a peak-heap measurement.

## Output storage results

Measured September 17, 2026 on macOS/Apple Silicon. JDK 21.0.12.1 and 25.0.4.1
produce identical snapshot counts and compact storage sizes. Each continuous
iteration prints 1,024 bytes; the burst workload prints 1,024 bytes once before
looping. All executable lines and main exit are captured.

| Workload | Iterations | Snapshots | Eager output array bytes | Compact array bytes | Compact bookkeeping bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Burst | 50 | 54 | 56,000 | 1,104 | 1,776 |
| Burst | 100 | 104 | 108,800 | 1,104 | 3,376 |
| Burst | 200 | 204 | 214,400 | 1,104 | 6,576 |
| Continuous | 50 | 103 | 2,614,496 | 65,616 | 3,344 |
| Continuous | 100 | 203 | 10,348,896 | 131,152 | 6,544 |
| Continuous | 200 | 403 | 41,177,696 | 262,224 | 12,944 |

At 200 continuous iterations, output storage decreases from 39.27 MiB of eager
arrays to approximately 269 KiB including compact bookkeeping: **99.3% less for
this measured portion of the trace**. The emitted output is 200 KiB. This is not
a claim of a 99.3% reduction in total process memory.

Raw data: eager [JDK 21](benchmarks/output-memory-jdk21.json) and
[JDK 25](benchmarks/output-memory-jdk25.json); compact
[JDK 21](benchmarks/output-storage-compact-jdk21.json) and
[JDK 25](benchmarks/output-storage-compact-jdk25.json). Artifact and harness hashes
are recorded in the files. The earlier eager harness names array capacity
`retainedOutputPayloadBytes`; the extended harness uses
`retainedOutputArrayCapacityBytes` to distinguish capacity from logical output.

## End-to-end checks

Three fresh-process runs per workload compared the pre-storage-change artifact
with the compact implementation, sequentially on the same machine/JDK 25.

| Workload | Median before | Median after | Median peak RSS before | Median peak RSS after |
| --- | ---: | ---: | ---: | ---: |
| Loop | 1.862 s | 1.815 s | 167.1 MiB | 184.8 MiB |
| Output | 2.708 s | 2.617 s | 283.8 MiB | 281.1 MiB |
| Collections | 2.102 s | 1.895 s | 189.6 MiB | 186.8 MiB |
| Sources | 2.255 s | 2.209 s | 178.3 MiB | 175.0 MiB |

All four normalized trace hashes and retained-byte counters match. Elapsed times
do not show a regression in this sample. Loop peak RSS increased; its baseline
runs ranged from 166.8 to 184.8 MiB, while updated runs ranged from 184.2 to
185.6 MiB. These measurements do not demonstrate a general RSS improvement. The
retained-output measurement above is the evidence for the specific storage gain.
A separate three-run loop repeat observed overlapping RSS ranges: 166.8–185.1 MiB
before and 167.0–185.4 MiB after. This supports treating the first loop median
difference as run-to-run variation rather than a demonstrated storage regression.
[Repeat data](benchmarks/output-storage-loop-repeat.json).
Raw benchmark runs: [before](benchmarks/output-storage-before.json) and
[after](benchmarks/output-storage-after.json).

The earlier source-index optimization reduced local source-heavy median elapsed
time from 2.530 to 2.196 seconds across three runs, with equivalent normalized
traces. Raw runs: [before](benchmarks/source-index-before.json) and
[after](benchmarks/source-index-after.json). These local measurements are not a
promise of a general speedup.

## Validation

The implementation passed a clean JDK 25 package build with 396 tests and 100%
production line/branch coverage. A separate clean JDK 21 build passed 395 tests
with one JDK 25-only test skipped. All 24 compatibility fixtures passed on each
JDK without fixture changes. Six Python documentation/normalizer tests also pass.
Linux CI was configured previously but was not run locally for this change.

Fourteen additional before/after CLI comparisons covered both formats, ordinary
single/chronological/latest/accumulating output, full envelopes, snapshot-limit
stops, and trace-byte-limit stops with Unicode stdout and stderr. Normalized
payloads, exit codes, and envelope metadata/counters matched after excluding only
elapsed time. Tests additionally cover independent public output arrays, growth
and changed-prefix histories, incomplete UTF-8, logical accounting, lazy conversion
failures, spool cleanup, and unavailable temporary storage.
