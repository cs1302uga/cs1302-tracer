# Source preparation, retention, and dispatch review

Scope: the changes after `dea85e9` in the eight affected production Java files,
their callers and tests, the glossary, and the Java migration guide. Output formats
and existing CLI/batch policy differences must remain compatible. The agreed test
interfaces are source preparation, snapshot retention, trace dispatch, and existing
CLI/batch integration paths.

## Implemented modules

- `SourcePreparation` owns compilation files and parsed source evidence in one
  closeable scope. Discovery policies preserve submitted-only, neighbor discovery,
  and compiled-source fallback behavior. Batch preparation no longer depends on App.
- `SnapshotRetention` owns admitted history, byte accounting, final-output projection,
  and shared replacement/duplicate rules. Admission preserves the existing budget
  check order. Session cancellation and stop reasons remain in `TraceSession`.
- `TraceDispatch` selects capture behavior before rendering and adapts the existing
  dedicated and persistent guest implementations. Their event loops remain separate.
  Modern and PyTutor rendering consume captured results without launching guests.

The [Java migration guide](../guides/java-module-migration.md) documents the new
interfaces. Existing Java helper entry points remain available.

## MD-001 — Synchronize the output test's pipe writers

Resolved separately in `ec2b0be`. The retention commit hook observed `first` instead
of the expected `first trailing` in the existing consecutive-job output test. Its
unflushed `PipedOutputStream` writes could leave the drainer asleep until the same
one-second deadline used by `JobOutput.drain`. Inspection of the installed JDK's
pipe implementation confirmed that `flush` notifies waiting readers.

The test now flushes each write before waiting for the drainer. Its expected output,
retained-prefix assertions, and consecutive-job checks are unchanged. The focused
output tests and the normal clean commit hook passed. No production output behavior
was changed to resolve this test synchronization defect.

## Balanced review

Reviewed preparation cleanup and source-root selection, atomic admission and budget
ordering, cancellation checks, output materialization and projection, batch partial
results, guest reuse, dispatch selection, and both rendering paths. Compared old and
new caller behavior, including null versus empty breakpoint selections and bounded
CLI versus batch finalization. No additional actionable findings were identified.

New behavior tests use public interfaces: real compilation and cleanup, neighbor
source evidence, submitted-file type resolution, rejected snapshot replacement,
output projection without accounting mutation, actual dedicated tracing modes, and
persistent guest reuse with isolated output and static state. The private batch
projection test was replaced by public retention/session tests. Existing writer and
Gson adapter contract tests remain; their reflective class references follow the
moved implementation. No new reflective tests were added.

## CodeQL re-review

CodeQL 2.27.2 analyzed a fresh database built by tracing Homebrew JDK 25 compilation
with Java 21 bytecode output. The database source archive matches all 53 production
Java files byte-for-byte. Database quality passed with zero extractor errors; the
explicit suite verification resolved 387 queries.

The approved configuration remains official security-and-quality plus experimental,
installed Trail of Bits Java 0.3.0, installed GitHub Security Lab Java 0.8.2 with its
matching extension pack, default remote and additional local sources, and the
previously verified Picocli/JDI project model. No new model gap was established.

The unfiltered result contains 172 records: 53 reviewed alerts and 119 diagnostic
inventory/partial-path records. All 53 alert causes match the prior scan by query
and source statement. Rechecked their existing resolutions in
[the architecture review](architecture-review.md), including the caller-selected
neighbor-source read relocated to `SourcePreparation`. Those CLI reads retain their
existing input policy; streamed-source destination containment remains separate.
No new alert causes or unresolved findings remain. No results were suppressed.

Artifacts are retained in `target/static_analysis_codeql_2/`, including the fresh
database, exact compiler inputs, explicit suite, model pack, quality/suite logs,
source-match evidence, unfiltered SARIF, and location-specific triage. These generated
artifacts are not committed. This review does not validate hosted isolation or Linux
execution and does not claim that resolved scanner alerts prove security.

## Validation

- Java 21: fresh compilation and full package build passed, with 745 tests discovered,
  one expected Java 25-only skip, and 100% production line and branch coverage.
- All 24 committed output fixtures passed unchanged on both Java 21 and Java 25.
- Source preparation and retention each passed the normal clean Java 25 commit hook.
  The dispatch change uses that same full-suite, Checkstyle, warnings-as-errors,
  and 100%-coverage staged-change gate.
- Executable documentation and release metadata checks passed, as did eight
  documentation-tool tests and five fixture-normalizer tests.
- The documentation site built successfully; links and fragments passed across
  89 rendered pages.

The only failed full-suite attempt was the unflushed-pipe test described in MD-001.
It was corrected and committed separately; no test assertions or coverage gates
were weakened, and no commit hook was bypassed.
