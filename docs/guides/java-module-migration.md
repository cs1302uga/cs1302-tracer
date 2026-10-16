# Java tracing modules

The CLI options and serialized trace formats remain unchanged. Java callers can use the
preparation module to keep compiled files and source evidence alive for the same scope:

```java
try (SourcePreparation prepared = SourcePreparation.prepare(
        source, inputPath, SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED)) {
    // Pass prepared.compilation() and prepared.units() to the tracing operation.
}
```

`SUBMITTED_ONLY` uses submitted sources without neighboring source discovery or a parser
search root. `NEIGHBORS` discovers neighboring sources and uses the discovered root for
resolution. `NEIGHBORS_OR_COMPILED` also falls back to the prepared source directory when
no original root is available. These preserve the existing field-only, bounded CLI, and
ordinary CLI/batch preparation policies respectively.

Closing the preparation releases its compiled files. Compilation or source-evidence
preparation failures release those files before propagating the original failure. The
source evidence list is immutable; its JavaParser compilation units remain mutable.

`CompilationHelper.compile`, `App.parseSource`, and `App.discoverAllCompilationUnits`
remain available for existing Java callers. New tracing callers should use
`SourcePreparation.prepare` so they do not have to coordinate compilation, source
resolution, and cleanup themselves.

`TraceSession` now delegates admitted history and retained-byte accounting to
`SnapshotRetention`. Existing session calls remain available. Cancellation, deadlines,
extraction limits, and the first stop reason remain session responsibilities.

For direct retention use, supply the effective limits, accumulation policy, and a
cancellation checkpoint when constructing `SnapshotRetention`. `commit` validates and
sizes a completed snapshot before changing the history. Its `LimitExceeded` exception
carries the existing machine-readable budget reason. A replacement must fit alongside
the old history before the old state is removed, preserving existing limit behavior.

`snapshots()` returns canonical retained history. `targetedSnapshots()` produces the
legacy final-output presentation for selected locations without changing that history
or its accounting. Session callers use `TraceSession.targetedSnapshots()`, which keeps
canonical output prefixes after a `trace_limit` stop. `JobOutput` continues to own the
finished output bytes.
