# Source and input observation review

> Historical review: findings, source locations, interface terminology, counts, and
> validation claims below apply to the stated revision scope, not automatically
> to the current checkout. Artifact paths identify local evidence from that run
> and may not exist in another checkout. For current supported behavior, see
> [Java integration](../reference/java-api.md) and
> [architecture](../contributing/architecture.md).

Scope: changes after `e736d82` to source observations and per-job input observation,
including their capture callers, tests, glossary, and Java migration guide. Java
interface changes are allowed; serialized formats and existing inference/input
semantics must remain compatible.

## Source observations

`SourceAnalysis` now owns source index construction, method-key matching,
final-variable lookup, lambda reconstruction, and selection at a captured line.
Capture consumes observations instead of interpreting exposed maps. The existing
first-assignment fallback and exact lambda text are preserved. Reified type metadata
and source preparation retain their existing responsibilities.

The removed map accessors and their replacements are documented in the
[Java migration guide](../guides/java-module-migration.md).

## Input observation

`InputObservation` owns reader subscriptions, initial standard-input identity and
fallback lookup, logical read recognition, and the per-job cursor. It incorporates
the former reader-tracking implementation and uses the existing cursor algorithm.
Dedicated and persistent guests retain their distinct event loops and request
cleanup. A new observation is created for every execution, including reused guests.
Nested-read suppression, unrelated-stream exclusion, and UTF-16 offsets remain
unchanged.

## Test design and balanced review

The agreed test seams are source observations from real parsed source, input
observation from debugger events, and real CLI/batch execution. The initial tests
failed because the new interfaces did not yet exist, then passed after implementation.
Source tests cover overload matching, exact lambda text, reassignment, fallback,
final flags, and unavailable evidence. Input tests cover partial UTF-8 reads,
initially unavailable identity becoming available, unsupported metadata, and fresh
cursors. Batch tests exercise consecutive non-ASCII input jobs through persistent
and dedicated multithread guests, including nested readers and unrelated streams.

Affected reflective source/helper tests now assert behavior through the new public
interfaces. Existing event-loop tests retain their original assertions while using
the new observation owner. No coverage gate or fixture expectation was weakened.
The first full coverage run exposed two obsolete null-input fallback branches in
snapshot capture. Both guest paths now always create an observation owner, so those
branches were removed instead of adding tests for an impossible execution state.

Balanced review inspected old/new matching and selection rules, identity lookup
timing, reader recognition, registration cleanup, callers, tests, and documentation.
An independent final static review found no actionable findings. Runtime validation
is recorded separately below.

## CodeQL

A fresh Java database was built with CodeQL 2.27.2 by tracing Homebrew JDK 25
compilation targeting Java 21. All 53 production sources match the database archive
byte-for-byte. Database quality passed with zero extractor errors, and the explicit
suite resolved 387 queries.

The approved scan used official security-and-quality and experimental suites,
Trail of Bits Java 0.3.0, GitHub Security Lab Java 0.8.2 and its matching extensions,
default remote plus local sources, and the previously verified Picocli/JDI model.
Inspection found no new model gap requiring an extension.

The 172 raw records match the prior scan by query and source statement: 53 alerts
retain their existing resolutions in [the architecture review](architecture-review.md)
(CQ-002 through CQ-006), and 119 records are diagnostic inventory or partial paths.
There are no new alert causes or unresolved findings. Results were not suppressed.
Generated database, source hashes, quality/suite logs, raw SARIF, comparison, and
location-specific triage are retained in `target/static_analysis_codeql_4/`.

## Validation

- Java 25: 750 tests passed, with no failures or skips; 100% production line and
  branch coverage.
- Java 21: a fresh staged-file export compiled and packaged successfully; 750 tests
  discovered, one expected Java 25-only skip, and 100% production line and branch
  coverage.
- All 24 committed output fixtures matched unchanged on both Java 21 and Java 25.
- Checkstyle and compiler warnings-as-errors passed. Executable documentation and
  release metadata checks passed.
- Both commits use the normal clean staged-file commit hooks; no hook was bypassed.

Runtime logs and red/green evidence are retained under `target/module-validation/`.
The scan and tests cover the local Java CLI/batch contract; they do not establish
hosted isolation or validate other operating systems.
