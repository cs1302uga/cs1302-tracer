# Retained output storage investigation

Measured September 17, 2026 against the production implementation committed in
`90d64bb`. The artifact hash and JDK identity are recorded in each result file.

## Method

`scripts/profile_output.py` builds a temporary Java instrumentation agent and
traces actual guest programs through `DebugTraceHelper.traceChronological`.
Each workload/size runs in a fresh JVM with a 512 MiB host heap. Breakpoints cover
all valid lines and main exit is included. This uses the library directly without
CLI limits, so large cases can expose growth that default limits would stop.

The burst workload prints 1,024 bytes once and then loops without output. The
continuous workload prints 1,024 bytes on every iteration. Both retain all
snapshots, validate their output contents and lengths, and count distinct output
arrays by identity. `Instrumentation.getObjectSize` measures each array's shallow
size, including its JVM header/alignment. An array referenced more than once is
counted once. All arrays remain reachable through the snapshot list when counted.

This is a measurement of the arrays retained by snapshots, not a heap-dump
dominator analysis or a measurement of the entire trace's retained heap. It
excludes stack/heap models, compiler/parser state, drainer capacity, temporary
allocations, and serializer models/strings. The shared-payload lower bound is
only the final output length; it is not an implemented or measured optimization.

## Results

| Workload | Iterations | Snapshots | Output array bytes | Final output bytes |
| --- | ---: | ---: | ---: | ---: |
| Burst | 50 | 54 | 56,000 | 1,024 |
| Burst | 100 | 104 | 108,800 | 1,024 |
| Burst | 200 | 204 | 214,400 | 1,024 |
| Continuous | 50 | 103 | 2,614,496 | 51,200 |
| Continuous | 100 | 203 | 10,348,896 | 102,400 |
| Continuous | 200 | 403 | 41,177,696 | 204,800 |

The continuous workload retains about four times as many output bytes each time
iterations double. At 200 iterations, output arrays occupy 39.27 MiB for 200 KiB
of final output. Repeated snapshots also copy unchanged output in the burst case.
These results establish an output-storage problem independently of process RSS.

Raw results: [JDK 25](benchmarks/output-memory-jdk25.json) and
[JDK 21](benchmarks/output-memory-jdk21.json). Each workload's structural counts
and array sizes are checked across the two JDKs; these are deterministic storage
measurements, not latency estimates requiring repeated timing samples.

## Compatibility constraints and implementation sequence

`ExecutionSnapshot` is a public record with cumulative mutable `byte[]` components.
Its constructors, accessors, record shape, and callers' ability to mutate an array
are part of the existing Java interface. Returning a shared array for unchanged
output would introduce cross-snapshot mutation. Replacing the record with a class
or changing component types would affect record patterns and reflection even if
some constructor/accessor signatures remained available.

The recommended seam is between internal capture/retention and public snapshot
materialization. Preserve the public record and existing trace methods. Add an
internal compact capture representation for CLI execution and adapt the public
library path to materialize independent cumulative arrays. The legacy eager
library result will still incur cumulative storage costs; its compatibility and
the compact CLI's memory behavior must be described separately.

Implement and validate in this order:

1. Introduce an internal append-only chunk store with immutable prefix offsets.
   Keep stdout and stderr distinct. Capture offsets at each existing stream sync
   point; handle sanitized stderr explicitly so JVM banner filtering stays intact.
   Test prefix stability across append, empty output, and chunk/UTF-8 boundaries.
2. Make compact snapshots the internal capture and retention representation.
   Centralize retained/replaced/suppressed snapshots and trailing-output refresh
   in the session owner. Adapt public trace results to independent byte arrays;
   preserve the record and verify mutation isolation between materialized results.
3. Adapt both CLI serializers to consume compact snapshots. Audit the serializers'
   cumulative String retention as well: array savings alone do not bound peak
   serialization heap. Streaming JSON can preserve the cumulative wire schema
   while releasing each materialized prefix after emission, but requires handling
   envelope completion, output failures, and partial results deliberately.
4. Preserve existing logical `traceBytes` accounting and stop behavior initially.
   Physical storage reduction must not silently weaken budgets. If physical-memory
   accounting is later introduced, give it a separately documented contract.
5. Run all compatibility fixtures on JDK 21/25, strict production coverage on
   JDK 25, and the existing lifecycle/budget regressions. Compare byte-for-byte
   normalized modern/PythonTutor payloads and bounded-stop counters. Measure the
   compact arrays/chunks directly and separately measure end-to-end peak heap,
   including serialization, before claiming a CLI memory improvement.

The measurement gate is complete. Shared storage remains an implementation task;
this investigation does not change production behavior or claim memory savings.
