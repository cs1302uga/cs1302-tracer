# Project audit and proposed improvement plan

Audited September 17, 2026, at commit `8890078`.

The project has a strong validation baseline: bounded execution, cleanup tests,
two output formats, compatibility fixtures, and a documented boundary between
tracing and hosted isolation. The next investment should be correctness at the
input and snapshot boundaries, followed by measured performance improvements.
This document records the original audit and proposal. Implementation progress and
current validation are recorded in [the improvement log](../IMPROVEMENT_PLAN.md).
The findings and line references below describe the audited baseline, not the
updated working tree.

## Validation and scope

- Clean Maven wrapper package on macOS: **passed on OpenJDK 21.0.12.1 and
  OpenJDK 25.0.4.1**.
- JUnit on JDK 21: **368 tests discovered, 367 passed, one skipped**; no failures
  or errors. On JDK 25: **all 368 passed**, with no skips, failures, or errors.
- Checkstyle and the normal package-specific JaCoCo gates: **passed on both JDKs**.
- The JDK 25 JaCoCo report records **100% production line and branch coverage**
  (3,117 lines and 1,772 branches). The optional strict coverage profile was not
  separately executed.
- Compatibility verification: **all 24 cases passed on both JDKs**, with fixtures
  unchanged.
- Python fixture-normalizer tests: **all five passed**.
- Additional subprocess probes reproduced the findings below using real guest
  JVMs on both JDK 21 and JDK 25. The JDK 25 rerun confirmed the large-stdin
  timeout, incorrect final metadata, duplicate envelope terminal step, actual
  modern field names, and multi-file line-number collision.
- JDK 25 is installed through Homebrew at `/opt/homebrew/opt/openjdk@25` and is
  now registered with jenv. The checkout's `.java-version` selects `25`, and both
  `java -version` and `./mvnw -version` resolve to OpenJDK 25.0.4.1. Validation was
  repeated with ordinary `./mvnw -B -ntp clean package` and
  `python3 examples/verify.py`, without explicit Java-path overrides.
- Linux was not run locally in this audit.

This is a source and behavior audit, not an exhaustive concurrency, dependency
vulnerability, or hostile-code isolation assessment. Existing hosted-runner
requirements remain appropriate; no new hosted deployment is proposed here.

## Confirmed findings

### 1. High: guest stdin can block before the guest is resumed

Evidence: `DebugTraceHelper.java:232` and `:946` call `writeGuestStdin` before
entering the event loop. The helper at `:1214` synchronously writes the entire
input to a guest launched in suspended state. Output drainers and the main
cleanup `try/finally` are also established after this write.

Reproduction: trace this program with `--result-envelope --timeout-ms 3000` and
`--stdin-file` containing ASCII `x` characters:

```java
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println(System.in.readAllBytes().length);
    }
}
```

- 16 input bytes: completes, stdout is `16\n`.
- 1,048,576 input bytes: stops with `timeout`, zero snapshots, and empty stdout.

The large write fills the pipe before the guest can consume it. The bounded
watchdog ends the wait; without a deadline this ordering risks an indefinite
wait. The latter is a code-derived risk, not an unlimited-mode reproduction.

Proposed fix: establish process ownership and cleanup immediately after launch,
then feed stdin concurrently with event processing. Own and close the input writer
as part of the tracing session. Add an explicit guest-input byte budget because
`App.resolveGuestStdin` currently reads an entire input file before opening a session.

Acceptance: small and large inputs complete, EOF arrives correctly, an early-closing
guest is handled, and cancellation/timeout leaves no guest or writer behind.

### 2. Medium: final-variable metadata ignores declaration scope and parameters

Evidence: `DebugTraceHelper.java:1565` builds a method-wide set of final local
names. `:1806` determines finality using name membership alone. The index scans
`VariableDeclarationExpr`, omitting final parameters and constructor declarations.

Reproduction with `trace -a -f modern`:

```java
public class Main {
    public static void main(final String[] args) {
        { final int x = Integer.parseInt("1"); System.out.println(x); }
        { int x = 2; x++; System.out.println(x); }
    }
}
```

In a multiline version of this program, snapshots report the mutable second `x`
as `final: true`, including after it changes to 3. They report `args` as
`final: false`. This is incorrect educational metadata in an advertised feature.

Proposed fix: index declaration identity, owning callable, and lexical scope;
resolve a visible JDI local to its applicable declaration. Include parameters
and constructors. Review lambda metadata for the same name-and-line assumptions.

Acceptance: exact metadata assertions in both formats for disjoint scopes,
final parameters, constructors, and nested declarations, without changing schemas.

### 3. Medium: envelope mode changes the chronological step sequence

Evidence: `snapshotTheWorld` commits every extracted snapshot to `TraceSession`
at `DebugTraceHelper.java:1429`. The chronological event loop suppresses redundant
main-exit snapshots only in its separate list (`:1051`). Ordinary output uses
that list; `App.executeBoundedSource` returns `session.snapshots()` instead.

Reproduction:

```java
public class Main {
    public static void main(String[] args) {
        int x = 1;
        System.out.println(x);
    }
}
```

For source formatted with executable lines 3, 4, and 5, ordinary
`trace -a -f modern` emits `[3, 4, 5]`; adding `--result-envelope` emits
`[3, 4, 5, 5]`. A transport/status option therefore changes the visualization.

Proposed fix: use one authoritative snapshot-retention path, with semantic
deduplication before retained-state publication. Keep captured-work counters and
retention accounting explicit, so removing duplicate output does not silently
weaken resource limits. Consolidate final-output refresh through the same owner.

Acceptance: equivalent payload sequences with and without an envelope for both
formats and all capture modes; stopped jobs still return only complete snapshots.

### 4. Medium: the README's modern JSON example describes different field names

Evidence: `README.md:111` shows `stack` with `variables`; real modern output and
the model use `callStack` with `locals`. The live probes confirmed the latter.
This can lead consumers to build against an incorrect example.

Proposed fix: replace illustrative schema fragments with checked output examples,
and document which fields are optional or vary with options. Keep the legacy
payload schemas distinct from the versioned result envelope.

Acceptance: documentation examples parse and match the actual output structure;
automated checks catch future field-name drift.

## Improvement opportunities

### Performance and module boundaries

- **Prepare source metadata once per compilation.** `snapshotTheWorld` constructs
  a new `AstTypeResolver` and rebuilds lambda/final maps on every snapshot
  (`DebugTraceHelper.java:1385`). Submitted sources are also parsed at several
  pipeline stages. A compilation-owned source index could serve all snapshots
  while keeping runtime object/type state local to each snapshot.
- **Store output once internally.** Each snapshot copies cumulative stdout and
  stderr (`DebugTraceHelper.java:1412`). If a program emits similar output per
  step, cumulative copies can grow quadratically with step count. Store a shared
  append-only output buffer with snapshot offsets, then materialize the existing
  external schema during serialization. Wire output remains cumulative unless a
  separately versioned format is introduced.
- **Measure before refactoring.** Record wall time, allocation/peak heap, snapshot
  count, and retained-byte accounting for loops, output-heavy programs, collections,
  and multi-file examples. Per-snapshot stream synchronization and JSON accounting
  are additional costs to profile, not presumed bottlenecks.
- **Extract around ownership.** The 2,211-line `DebugTraceHelper` combines launch,
  event handling, input tracking, snapshot extraction, and AST indexing. Favor a
  small session-owned execution component and an immutable source-index component,
  introduced through the fixes above, over a wholesale rewrite.

The first two opportunities already appear in `IMPROVEMENT_PLAN.md`; this audit
confirms their code basis but does not claim a measured speedup.

### Multi-file breakpoint identity

Selected breakpoint storage uses a bare integer line number in both the helper
and `TraceSession`. In a two-file probe with both files hitting line 4, the
non-accumulating envelope reports two captured snapshots and one retained state.
This matches the documented latest-per-line policy, but cannot represent the
latest state independently for each source location.

Introduce an internal `(source path, line)` identity and an optional qualified CLI
selector such as `--breakpoint Main.java:4`. Preserve bare-line legacy behavior
and specify its matching semantics explicitly. Any changed dictionary key shape
needs an opt-in or versioned contract, not a silent compatibility break.

### Source discovery and whole-job bounds

`App.discoverAllCompilationUnits` walks and parses every Java file under the
inferred source root, including unrelated files, before the tracing deadline
starts. Submitted-source caps do not account for these discovered files. This is
a documented boundary of current limits, but can still surprise instructors
tracing one file in a large checkout.

Prefer the actual compiled dependency set for metadata discovery. Make discovery
limits explicit and evaluate a separate whole-job deadline if predictable total
latency is required. Keep compiler isolation and hosted execution in their own
project rather than describing an in-process deadline as a sandbox.

### Build and test consistency

- Use `./mvnw` consistently in the Makefile, CI, and contributor instructions;
  most current commands use a globally installed `mvn` despite a pinned wrapper.
- Decide whether the optional 100% whole-production coverage profile is a merge
  requirement or a local policy. CI currently enforces the narrower default
  gates. The JDK 21 run covers every production line but misses two branches;
  the strict profile would consequently fail its branch threshold on that run.
  The JDK 25 run reports 100% line and branch coverage across production code.
  Neither normal `package` run executes the optional strict profile, so distinguish
  the measured coverage from enforcement of that profile.
- Add real semantic boundary cases even where coverage is already high. The
  confirmed bugs above survive the existing tests and near-total branch coverage.
- Clarify supported JDKs in setup docs and keep JDK-specific tests explicit.
  Validate the fixes on the existing Linux 21/25 and macOS matrix.

## Proposed delivery sequence

| Phase | Scope | Completion gate |
| --- | --- | --- |
| 1: Startup reliability | Concurrent guest-input feeding, immediate lifecycle ownership, input-size policy | Large-input, EOF, early-close, cancellation, and timeout subprocess regressions pass on JDK 21/25 |
| 2: Trace correctness | Scoped final metadata; one authoritative retained-snapshot sequence | New semantic assertions pass in both formats; envelope parity holds; existing 24 fixtures remain unchanged except reviewed corrections |
| 3: Consumer contract | Accurate README examples, payload contract checks, clarified CLI defaults and coverage policy, consistent Maven wrapper commands | Examples verified against actual JSON; documented build path passes the supported CI matrix |
| 4: Measured optimization | Baseline workloads, per-compilation source index, shared output storage, focused dependency discovery | Trace equivalence plus recorded before/after latency and memory results; retention-limit behavior remains tested |
| 5: Optional capability | File-qualified breakpoints; whole-job deadline if needed | Explicit compatibility design and acceptance tests before changing public behavior |

### Phase 1 — Make startup and input delivery reliable

Priority: high. Start here; the input bug prevents otherwise valid programs from
executing. Deliver lifecycle/input feeding and the input-budget policy as separate
reviewable changes if the policy would delay the startup fix.

1. Turn the small/large stdin reproduction into subprocess regressions with outer
   deadlines and cleanup assertions.
2. Establish guest ownership immediately after launch, including cleanup on
   initialization failures. Run a session-owned input feeder concurrently with
   event processing, close it to deliver EOF, and join it during cleanup.
3. Cover guests that never read input, close stdin early, exit early, or are
   cancelled while a write is blocked. Preserve the first observed stop reason.
4. Add a guest-input byte limit enforced while loading input, before allocating
   the complete string. Specify its default, zero/unlimited behavior, envelope
   representation, and stop code alongside existing limits.

Exit gate: the 1 MiB reproducer completes under a reasonable test deadline;
timeout and cancellation leave no guest or feeder alive; output remains valid in
ordinary and envelope modes. Run on both supported JDKs and the CI OS matrix.

### Phase 2 — Correct snapshot contents and retention

Priority: high for educational correctness. Follow Phase 1 for lifecycle-related
changes; the metadata fix can be reviewed independently.

1. Replace method-wide final-name membership with declaration-aware lookup,
   incorporating callable identity, lexical scope, parameters, and constructors.
   Include nested declarations and overloaded methods in focused tests. Review
   lambda lookup for the same scope assumptions and record any additional defect
   before expanding the implementation.
2. Consolidate snapshot retention so ordinary and envelope output consume the
   same selected sequence. Apply redundant-terminal-step suppression consistently.
3. Keep extraction counts distinct from retained counts. Ensure discarded or
   replaced snapshots and trailing output are accounted for consistently, and
   preserve atomic publication of complete snapshots on controlled stops.

Exit gate: exact final flags in both formats; equivalent ordered payloads for
ordinary/envelope chronological, selected, accumulating, and end-only capture;
partial-result and budget regressions pass. Normalize only already-approved
identity differences when comparing results. Review genuine bug corrections
individually rather than regenerating all fixtures.

### Phase 3 — Make contracts and build checks dependable

Priority: medium. Documentation corrections can start immediately; finalize
behavior examples after Phases 1–2.

1. Replace inaccurate README JSON with verified examples from the real serializer.
   Add lightweight structural assertions for those examples and explain optional
   fields, source-file context, defaults, and envelope versus payload versioning.
2. Standardize Makefile, CI, and contributor commands on `./mvnw`. Document the
   JDK 25 jenv setup and continued JDK 21 compatibility.
3. Recommended coverage policy: retain ordinary build/compatibility checks across
   the supported matrix and enforce the existing strict coverage profile in a
   designated JDK 25 CI job. Document why JDK 21's skipped JDK-specific execution
   can produce different coverage. Validate this policy before enabling a new
   required check; do not assume local coverage guarantees Linux coverage.
4. Promote the new behavior regressions into CI. Keep coverage percentages as
   supporting evidence alongside semantic assertions.

Exit gate: documentation examples match generated JSON, the documented build
commands work, and CI visibly runs each promised check. Publish correctness fixes
after Phases 1–3 without waiting for optimization work.

### Phase 4 — Improve performance with measured changes

Priority: medium; dependent on the correctness and contract gates above.

1. Establish repeatable baseline workloads covering many snapshots, substantial
   stdout/stderr, collections, and large source trees. Record JDK, machine,
   workload size, elapsed time, allocation/peak heap, and accounting counters.
2. Build immutable source/type/lambda/final indexes once per compilation while
   keeping runtime type inference and object identities snapshot-local.
3. Retain output once internally with per-snapshot offsets. Materialize cumulative
   output at the serializer boundary, preserving the public schema and documenting
   how internal accounting changes, if at all.
4. Restrict source discovery to the compiled dependency set where possible. Add
   discovery-specific limits and diagnostics; test implicitly compiled sources,
   nested classes, and missing metadata before removing broad discovery.
5. Extract components around source-index and session ownership as these changes
   land. Profile stream synchronization and snapshot JSON accounting before
   attempting further optimization.

Exit gate: before/after measurements show an improvement on the target workload,
without unexplained regressions elsewhere; semantic traces remain equivalent;
source and retention limits still behave as specified. Deliver the optimizations
separately so a regression can be isolated and reverted.

### Phase 5 — Extend capabilities only after defining compatibility

Priority: optional, driven by instructor workflows after the earlier fixes.

1. Introduce `(source path, line)` internally and design qualified breakpoint
   selectors. Keep bare-line selectors working with their documented semantics;
   use an opt-in or versioned representation if output dictionary keys change.
2. Decide whether a whole-job deadline is needed based on source-discovery and
   compilation measurements. If required, define the included phases and use an
   externally supervised worker process when reliable termination of compiler or
   parser work is necessary.
3. Keep the hosted isolation runner a separate follow-up with its existing runner
   contract. It is not a prerequisite for the instructor reliability fixes.

Exit gate: file-qualified selectors retain independent states for identical line
numbers in different files, legacy selectors remain compatible, and any whole-job
deadline has tested termination and failure-reporting behavior.

Update `IMPROVEMENT_PLAN.md` as changes land, with actual validation and benchmark
results. This document remains the audit evidence and proposal.
