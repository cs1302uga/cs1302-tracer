# Tracer improvement plan

Status: first milestone implemented on `feat/bounded-tracing`; final validation
is recorded below. The separate Linux runner and listed follow-up work remain
outside this milestone.

## Objective and agreed scope

Make Tracer more reliable for trusted instructor examples and prepare it for a
separate runner that executes potentially malicious student submissions.

- Trusted instructor execution remains cross-platform and the default workflow.
- Hosted student execution targets Linux and assumes malicious inputs.
- A separate runner owns isolation for the entire parse, compile, and trace job.
  It may use a container/VM or an OS-enforced sandbox for direct server execution.
- Tracer owns trace budgets, cancellation, diagnostics, and structured results.
  These controls do not establish a security boundary.
- Existing Python Tutor JSON and CLI workflows remain backward compatible.
  Hosted result metadata uses a separately versioned, opt-in envelope.
- Limit-triggered stops retain completed snapshots and identify the result as
  incomplete. A forcibly killed job may have no recoverable trace.
- The first milestone delivers Tracer improvements and the runner contract.
  Building and validating the Linux runner is a follow-up project.

## Audit baseline

On September 12, 2026, `mvn test -B -ntp` passed 134 tests, Checkstyle, and the
configured coverage checks on local JDK 25. This was not a clean build or a
verification of every supported platform. CI currently tests Java 21 and 25.

Additional bounded probes reproduced two defects:

1. An absolute path in a streamed source delimiter wrote a source file outside
   the temporary compilation directory, into an audit-owned temporary location.
2. Tracing a `HashMap<String, String>` containing a null value threw a
   `NullPointerException` during map extraction.

Source inspection also found indefinite debugger waits, unbounded output and
snapshot retention, guest method invocation during inspection, executable
breakpoint discovery, and incomplete compilation resource cleanup.

## Delivery sequence

### Phase 1: Fix input containment, null handling, and compilation cleanup

Primary files: `CompilationHelper.java`, `TraceValue.java`, and their tests.

Work:

- Validate every streamed path before creating any source files. Reject absolute
  paths, parent traversal outside the working directory, duplicate normalized
  destinations, and invalid source destinations. Treat both separator styles
  consistently. Resolve destinations against a private temporary directory and
  verify containment; document the no-concurrent-writers assumption.
- Represent null map keys and values using the existing null value model. Do not
  enqueue null heap references. Verify both serializers preserve the distinction
  between null and an object reference.
- Close compiler file managers with explicit resource ownership. Clean temporary
  directories when writing, compilation, or entry-point selection fails.
- Remove or unregister per-compilation shutdown hooks after cleanup. Surface
  actionable cleanup failures without replacing the original compilation error.

Acceptance criteria:

- Absolute and escaping paths are rejected before any source is written.
- Valid packaged multi-file streams still compile and trace.
- Null map keys, null values, and both together serialize in both formats.
- Repeated successful and failed compilations leave no owned temporary files or
  accumulating compiler resources after their lifecycle ends.

### Phase 2: Define limits and the versioned result contract

Primary files: `App.java`, trace models, and a new result/limits model as needed.

Work:

- Define validated limits for elapsed tracing time, snapshot count, captured
  stdout/stderr bytes, heap objects, and elements inspected per snapshot. Include
  source byte/file caps for the hosted job interface and a total retained trace
  budget so many individually valid snapshots cannot grow without bound.
- Specify units, boundary behavior, precedence when limits coincide, and how
  callers explicitly select unlimited trusted operation.
- Keep existing invocations on the legacy output path. Add an opt-in result
  envelope around either supported trace format; do not insert envelope metadata
  into Python Tutor payloads.
- Define envelope version, trace format, status, stop reason, execution phase,
  diagnostics, effective limits, and counters. Distinguish normal completion,
  guest exception, compile failure, cancellation, limit stop, and tracer failure.
- Distinguish an empty trace from an unavailable trace. Include completed
  snapshots on recoverable failures and record dropped/incomplete extraction.
- Specify process exit codes and stdout/stderr ownership. Machine-readable
  results go to stdout; tracer diagnostics cannot corrupt them.
- Keep runner-enforced termination status distinct from guest-produced data.
  Student-controlled output must never serve as proof of successful isolation.

Acceptance criteria:

- Existing CLI invocations retain their payload shape and behavior on supported
  successful inputs. New options reject invalid budgets with useful diagnostics.
- Contract fixtures cover success, compile failure, empty trace, partial trace,
  cancellation, and unavailable trace after external termination.
- The contract specifies that Tracer deadlines cannot guarantee interruption of
  an in-process parser/compiler; the runner enforces an outer whole-job deadline.

Proposed default policy: retain existing defaults for trusted invocation; require
finite, explicit budgets from the future hosted runner. Choose an example hosted
budget profile using measurements from the reference examples, rather than
presenting arbitrary values as security guarantees.

### Phase 3: Enforce budgets across the tracing lifecycle

Primary files: `DebugTraceHelper.java`, `StreamDrainer.java`, `TraceValue.java`.

Work:

- Introduce one trace-session owner for the VM, output drainers, watchdog,
  cancellation state, budgets, and completed snapshots.
- Use a monotonic deadline and bounded event polling. Add a watchdog independent
  of the event handler so blocked JDI invocations cannot bypass the deadline.
- Make termination idempotent. Destroy the guest process when needed, wait for
  termination within a bounded cleanup interval, and close streams/watchdog
  resources. Leave reliable containment and descendant cleanup to the runner.
- Bound output retention at the reader, before allocating beyond its budget.
  Continue draining/discarding during shutdown as necessary to avoid pipe stalls.
- Check heap and element budgets before bulk allocation or fetching entire
  arrays. Apply cancellation checks throughout extraction and serialization.
- Commit snapshots atomically. If extraction exceeds a budget, retain earlier
  complete snapshots and discard the unfinished snapshot.
- Bound retained trace data and result generation. Avoid building an additional
  unbounded JSON string; never truncate serialized JSON into an invalid document.
- For selected-breakpoint mode without accumulation, retain only the most recent
  required snapshot per breakpoint rather than collecting every hit first.

Acceptance criteria:

- Bounded subprocess tests cover a loop with no requested breakpoint hits, an
  output flood, many breakpoint hits, a large reachable object graph, cancellation,
  and termination during extraction.
- Each recoverable limit returns valid envelope JSON with the expected stop
  reason and completed snapshots. Guest and drainer resources terminate.
- Test harnesses have their own hard deadline and process cleanup so a regression
  cannot hang CI. Memory/resource assertions run in isolated test processes.

### Phase 4: Make inspection behavior explicit and remove preliminary execution

Primary files: `DebugTraceHelper.java`, `TraceValue.java`, breakpoint tests.

Work:

- Inventory all guest method invocations, including collection/map accessors and
  stream flushing. Document that these can change guest state or block.
- Add an inspection policy for student workloads that avoids guest method
  invocation. Use bounded field-based extraction; expose unsupported collection
  representations as ordinary objects or explicit diagnostics.
- Preserve the current richer inspection behavior for trusted examples initially.
  Do not silently claim equivalent collection semantics under the restrictive
  policy. Describe supported JDK representations and fallback behavior.
- Define output capture under restrictive inspection: capture bytes already
  emitted to pipes without invoking guest `flush`; document buffering limitations.
- Obtain executable line metadata from compiled class files without running the
  guest. Evaluate an implementation compatible with the Java 21 baseline before
  choosing a dependency or class-file reader.
- Preserve source-file identity internally. Keep existing numeric breakpoint
  semantics for compatibility; defer new file-qualified CLI syntax to a follow-up.

Acceptance criteria:

- `list-breakpoints` cannot trigger a guest static initializer or `main` method.
- Automatic all-line tracing does not perform a preliminary program execution.
- Restrictive inspection does not execute overridden collection accessors or a
  custom `PrintStream.flush`; unsupported extraction is reported predictably.
- Multi-file, nested-class, record, and unused-class fixtures yield executable
  locations without depending on runtime class loading.

### Phase 5: Lock compatibility and document the hosting boundary

Primary files: serializer/CLI tests, example tooling, `README.md`, `HACKING.md`, CI.

Work:

- Add semantic golden comparisons for representative existing Python Tutor
  workflows: default snapshots, selected breakpoints, accumulation, all-line
  traces, multi-file input, strings, aliases, cycles, generics, and exceptions.
- Normalize nondeterministic object IDs in comparisons while verifying reference
  identity, aliasing, step ordering, and values. Do not normalize away defects.
- Add a noninteractive example regression command. Keep fixture verification
  separate from explicit regeneration; fix the documented `examples/test.sh`
  invocation, which currently requires an input file and generates output.
- Run required build/style/coverage and behavioral checks on Java 21 and 25.
  Include a cross-platform trusted smoke test if CI capacity permits.
- Correct claims that the launched JVM is sandboxed and that all production
  packages have enforced 100% coverage. Document actual coverage scope and the
  behavior-focused test strategy.
- Publish the runner contract: private workspace, sanitized inputs/environment,
  whole-job CPU/memory/process/output/deadline limits, restricted files/network,
  teardown, artifact validation, and authoritative external termination status.
- State that containers/VMs and direct Linux hosting must each satisfy that
  contract. No option in Tracer alone makes malicious submissions safe to run.

Acceptance criteria:

- Existing visualizer fixtures pass without consumer changes.
- Documentation accurately describes defaults, limitations, inspection policy,
  result schema, failure behavior, and which guarantees belong to the runner.
- The milestone is labeled preparation for hosted execution, not a completed
  malicious-code hosting solution.

## Follow-up improvements

After the first milestone, prioritize these independently measurable changes:

1. Cache immutable AST/type/lambda/final indexes once per compilation instead of
   rebuilding them for each snapshot. Verify trace equivalence and measure time.
2. Delivered during phase 3: heap traversal now uses a deque-backed work queue
   with deduplicated object IDs, enabling reference budgets before retention.
3. Store captured output once with per-snapshot offsets internally, materializing
   cumulative output only where serializers require it. Measure peak memory and
   runtime on output-heavy traces.
4. Complete focused module extraction around compilation ownership, VM lifecycle,
   snapshot extraction, and result serialization. Avoid a broad rewrite or
   abstractions introduced solely to split large files.
5. Implement the separate Linux runner and validate isolation for container/VM
   and direct OS-sandbox backends before enabling hosted student submissions.
6. Consider checkpointed trace artifacts if recovering partial output after a
   forced runner kill becomes a requirement. The first milestone does not promise
   recovery from process death or host memory exhaustion.

## Dependencies and release gate

Implement phases 1–5 as reviewable changes in that order. Phase 2 supplies the
contract used by phases 3 and 4; compatibility fixtures should be added alongside
each change and consolidated in phase 5. Defer performance tuning until bounded
behavior and compatibility are established.

The first milestone is complete when reproduced defects have regression tests,
all budgets have bounded failure tests, partial results are valid and explicit,
legacy visualizer fixtures pass, cleanup is verified, and the runner boundary is
documented. Release notes must distinguish bug fixes, opt-in behavior, remaining
limitations, and the separate work required for hosted isolation.

## Implementation outcome

Phases 1–5 are implemented in small commits on `feat/bounded-tracing`:

- Contained source destinations, null map entry handling, compiler ownership,
  and cleanup regression tests.
- Opt-in v1 result envelope, finite configurable budgets, monotonic watchdog,
  caller cancellation, atomic partial snapshots, and bounded output retention.
- Latest-only selected-breakpoint retention, including legacy CLI output, and
  explicit uncaught-exception/nonzero guest-exit results.
- FIELDS inspection without guest method invocation and ASM-based breakpoint
  discovery without guest execution.
- Eleven committed Python Tutor compatibility fixtures generated from `main`
  (`bd7626f`), a non-mutating verifier, normalizer tests, documentation, and CI
  coverage for Linux JDK 21/25 plus macOS JDK 21.

The result envelope additionally retains bounded stdout/stderr when a job stops
before producing its first snapshot. Trace-byte accounting is a documented data
budget, not an OS memory ceiling. Input parsing/compilation still require an outer
runner deadline, and forced process death can leave no recoverable JSON artifact.

See [bounded tracing](docs/BOUNDED_TRACING.md) for the contract and measured example
profile, and the [runner contract](docs/RUNNER_CONTRACT.md) for hosted isolation
requirements. Existing display-oriented collection decoding remains available in
TRUSTED mode; FIELDS reports its raw-field presentation in diagnostics.

### Final validation

Validated locally on macOS on September 12, 2026:

| Check | Result |
| --- | --- |
| Clean Maven package on JDK 21 | 164 tests passed; Checkstyle and configured JaCoCo gates passed. |
| Clean Maven package on JDK 25 | 164 tests passed; Checkstyle and configured JaCoCo gates passed. |
| Final JAR against Python Tutor fixtures on JDK 21 | All 11 cases passed. |
| Final JAR against Python Tutor fixtures on JDK 25 | All 11 cases passed. |
| Fixture-normalizer unit tests | All 4 passed. |
| Whitespace validation | `git diff --check` passed. |

The subprocess regressions verify timeout without breakpoint hits, output caps,
heap/element/retained-trace caps, snapshot caps, source byte/file caps, cancellation,
partial-state retention, restrictive inspection, nonzero guest exit reporting, and
that a timed-out guest process is no longer alive when the CLI exits. Linux CI and
the separate runner's isolation tests have not been executed in this local session.


## Instructor reliability follow-up — September 17, 2026

The follow-up prioritizes trusted instructor use. Agreed behavior:

- Ordinary CLI runs use finite limits with `--unlimited` as an explicit override.
- Successful JSON keeps its existing shape. Limit stops emit no stdout trace,
  diagnose the stop on stderr, and exit 3. Envelope mode retains partial traces
  with explicit incompleteness and its existing explicit-budget policy.
- TRUSTED inspection remains the default; FIELDS remains an explicit envelope option.
- Demonstrated incorrect values may change without changing the schema.
- Input highlighting describes logical consumption, excluding buffered lookahead.

Implemented corrections and before/after behavior:

1. Reading `"hello"` from `new Scanner("hello")` previously marked matching supplied
   stdin consumed. Reader tracking now checks delegate provenance before advancing.
2. Nested reader calls could count a single read repeatedly. Only the outermost
   observed read advances input, including both IO.readln overloads. Repeated lines,
   blank lines, Unicode, and EOF have JDK 25 regression coverage.
3. Cached numeric Scanner reads now use the original matched text so lookahead
   does not consume input and signs/leading zeros are preserved.
4. Raw UTF-8 byte consumption previously advanced UTF-16 indices as though bytes
   and characters were interchangeable. Highlighting now waits for complete code
   points, preserving the existing character-index schema.
5. Final output refresh now preserves the snapshot's stdin metadata.
6. Ordinary CLI sessions enforce the documented finite profile. Accumulated
   breakpoint output retains all budgeted hits; selected non-accumulating output
   retains only the latest hit. Existing successful fixtures are not regenerated.

The input observer is extracted into ReaderTracking, using bounded delegate-field
inspection without invoking guest methods. JDK 21 and 25 are the supported test
matrix. Arbitrary custom readers, alternate encodings, and mixing IO with other
stdin APIs remain outside the guaranteed input-highlighting contract.

Remaining proposals, ordered after the reliability work:

1. Cache immutable source-derived AST/type/lambda/final indexes per compilation.
   `snapshotTheWorld` currently rebuilds these for each snapshot. Require trace
   equivalence and measured improvement before changing that ownership boundary.
2. Store captured output once with snapshot offsets internally. Snapshot creation
   currently copies cumulative output; quantify retained-memory improvement before
   introducing a new representation.
3. Add an outer whole-job deadline if parsing/compilation hangs become an instructor
   requirement. Current tracing deadlines exclude those phases and source transport.
4. Keep the separately isolated hosted runner as a later project.


### Follow-up validation

- Clean Maven package on local JDK 25: 203 tests passed; Checkstyle and configured
  coverage gates passed.
- Clean Maven package on local JDK 21: 203 tests discovered, 202 passed, and the
  JDK 25-only IO regression skipped; Checkstyle and configured coverage gates passed.
- All 24 compatibility fixtures passed on both JDK 21 and JDK 25. This includes
  the 11 original Python Tutor cases, 11 modern equivalents, and logical stdin
  consumption in both formats. All 11 original fixture files remain unchanged.
- Five fixture-normalizer tests passed. Modern heap-entry IDs are normalized along
  with references and heap keys, while mismatched IDs and changed input offsets
  remain detectable.
- `git diff --check` passed. Linux CI was not run locally.


## Audit implementation follow-up — September 17, 2026

The follow-up implements the startup, correctness, and contract work from
[the audit](docs/AUDIT_PLAN.md), plus the source-index and dependency-discovery
optimizations. The original audit remains a dated baseline.

### Delivered behavior

- Guest input is fed concurrently with event processing. A guest runtime owner
  cleans up initialization failures and terminates the process before closing
  potentially blocked streams and joining the input feeder.
- Ordinary jobs now also allow 1 MiB of UTF-8 guest input by default.
  `--max-input-bytes` overrides this budget; zero disables it. Envelope mode keeps
  its explicit-budget policy. `input_limit` stops before compilation, exits 3, and
  includes `limits.inputBytes` in the envelope. File input is checked per chunk.
- Local finality uses declaring class, callable, and lexical scope, including
  final parameters and constructors. Disjoint scopes no longer share a final-name
  flag. Both output formats have real-guest regression assertions.
- Ordinary and envelope capture use the same retention decisions. A redundant
  terminal state counts as extracted work but is not retained. Output refresh
  updates the session and legacy result containers under the same accounting.
- Source-derived type, local, and lambda indexes are constructed once per trace.
  Runtime object identity/type propagation remains local to each snapshot.
- Metadata discovery follows compiled class SourceFile attributes instead of
  walking all Java files under the inferred source root. It includes nested and
  constant-only types. Source byte/file caps apply independently to discovered
  dependencies; compiler and symbol-solver work are still outside these caps.
- README JSON examples are checked against the existing verified record fixtures.
  Maven wrapper commands are used consistently. The JDK 25 CI build enables the
  strict production coverage profile; JDK 21 builds retain the default gates.

### Remaining decisions

The full shared-output/offset representation remains deferred. Existing snapshot
records expose cumulative byte arrays, and serializers build cumulative strings.
The source-cache benchmarks measure process-tree RSS rather than retained Java
heap, so they do not establish the benefit of replacing that representation.
A memory-specific profile and a compatibility-preserving design are the next gate;
no claim of bounded actual Java heap or elimination of cumulative output copying
is made here.

File-qualified breakpoints, a whole-job deadline, and the isolated hosted runner
remain optional Phase 5 work. Legacy line-only matching is unchanged. Lambda
reconstruction still uses source-name/line heuristics; this follow-up corrects
final metadata without broadening the lambda feature's guarantees.

### Validation and measurements

Benchmarks used three fresh-process runs per workload, running the baseline and
updated artifacts sequentially on the same macOS/JDK 25 machine. The baseline is
the working tree after the correctness fixes and before source-index caching and
compiled-dependency discovery. All four normalized trace hashes match.

| Workload | Median before | Median after | Peak RSS before | Peak RSS after |
| --- | ---: | ---: | ---: | ---: |

| loop | 1.855 s | 1.875 s | 183.0 MiB | 184.4 MiB |
| output | 2.656 s | 2.673 s | 287.8 MiB | 285.0 MiB |
| collections | 2.080 s | 2.123 s | 221.1 MiB | 184.5 MiB |
| sources | 2.530 s | 2.196 s | 196.5 MiB | 175.5 MiB |

The source-heavy workload improved by about 13% in elapsed time and 11% in peak
RSS. Collection peak RSS also decreased; the other elapsed-time differences are
small and do not demonstrate an improvement. These are local process-tree
measurements, not a claim of general speedup or retained-heap savings.
Raw runs: [before](docs/benchmarks/audit-before.json),
[after](docs/benchmarks/audit-after.json). Reproduce with
`python3 scripts/benchmark.py --jar PATH_TO_ARTIFACT`.

Baseline benchmark JAR SHA-256: `90979bc3cec03a6f6965376963c66266b0cd9ae5a90c142d9f7ab00fb495085e`.

Updated benchmark JAR SHA-256: `ca0fcf5e113970b9134a9cac2813f6d60e8c8f3776349cc268e3456a711a5e08`.


Final validation of the implementation:

| Check | Result |
| --- | --- |
| Clean package, JDK 25 with `-Ppre-commit-coverage` | 385 tests passed; Checkstyle and strict coverage passed. |
| Production coverage, JDK 25 | 3,239 lines and 1,858 branches covered; zero missed. |
| Clean package, JDK 21 | 385 tests discovered, 384 passed, one JDK 25-only test skipped; default gates passed. |
| Compatibility fixtures, JDK 21 and JDK 25 | All 24 passed on each JDK; committed fixtures unchanged. |
| Python normalizer and README contract tests | All six passed. |
| Whitespace validation | `git diff --check` passed. |

Linux CI was configured but not executed locally. The tests cover 1 MiB input and
EOF, early input closure/exit, timeout cleanup, partial initialization, cancellation
while joining the feeder, UTF-8 input-budget boundaries, lexical final metadata,
constructor/overload parameters in both formats, suppressed-snapshot accounting,
and bounded compiled-dependency metadata discovery. The original three correctness
regressions were observed failing before their fixes.
