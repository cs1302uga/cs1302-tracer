# Internal tracing modules

This is contributor guidance for `cs1302.tracer.internal`, not a supported library
integration guide. These public implementation classes are annotated `INTERNAL`
and carry no external compatibility promise. Library consumers should use
[Java integration and 4.0 migration](../reference/java-api.md).

The following describes the current internal ownership and call conventions.
Internal tracing code uses the preparation module to keep compiled files and source
evidence alive for the same scope:

```java
try (SourcePreparation prepared = SourcePreparation.prepare(
        source, inputPath, SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED)) {
    // Pass prepared.compilation() and prepared.units() to the tracing operation.
}
```

`SUBMITTED_ONLY` uses submitted sources without neighboring source discovery or a parser
search root. `SUBMITTED_OR_COMPILED` keeps submitted-only discovery while using the
private compiled-source directory for parser resolution; one-shot `Tracer.trace`
uses this policy under trusted inspection. `NEIGHBORS` discovers neighboring sources
and uses the discovered root for resolution. `NEIGHBORS_OR_COMPILED` also falls back to the prepared source directory when
no original root is available. These preserve the existing field-only, bounded CLI, and
ordinary CLI/batch preparation policies respectively.

Closing the preparation releases its compiled files. Compilation or source-evidence
preparation failures release those files before propagating the original failure. The
source evidence list is immutable; its JavaParser compilation units remain mutable.

`CompilationHelper.compile`, `App.parseSource`, and `App.discoverAllCompilationUnits`
exist under `cs1302.tracer.internal`; `App` here means the internal command class,
not the supported launcher. `SourcePreparation.prepare` keeps internal callers from
having to coordinate compilation, source resolution, and cleanup themselves.

`TraceJob` resolves effective tracing choices for the `JAVA`, `CLI`, `ENVELOPE`,
and `NDJSON` caller profiles. Typed Java requests go through `fromJava`; command
and wire adapters supply decoded settings to `resolve`. Finite instructor budgets
are the Java and ordinary CLI defaults; envelope and NDJSON defaults are unlimited.
Explicit budgets win. Multithread capture forces chronological capture and
field-only inspection, while still requiring modern output.

`CaptureIntent` detaches raw selection and hides caller-specific omitted/empty
conventions from execution. Call `selection()` at the existing validation point:
CLI validates before reading source; Java and NDJSON validate after compilation.
Java/NDJSON empty chronological selection means all valid lines; CLI chronological
selection uses all valid lines only when omitted. Ordinary CLI omission without
chronological capture selects final-state rendering; envelope and batch modes use
main-exit breakpoint capture. Envelope dispatch accumulates selected hits, while
session history follows the requested chronological/accumulation flags.
`retainsHistory()` supplies that separate history policy. Resolution does not own
guest lifecycle, cancellation, admitted snapshots, or result rendering.

`TraceSession` delegates admitted history and retained-byte accounting to
`SnapshotRetention`. These session calls are internal. Cancellation, deadlines,
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

`TraceDispatch` captures execution before rendering. `dedicated()` launches and cleans
up a fresh guest per capture. `persistent(guest)` borrows a `PersistentGuestSession`;
its caller still closes or recycles that guest. Both adapters retain their existing
event loops.

`Selection.select(chronological, accumulate, breakpoints)` preserves the distinction
between a null breakpoint selection and an explicitly empty list. Null means all
valid lines for chronological capture, or final-state capture otherwise. An explicit
list selects breakpoint capture, including the existing empty-list main-exit behavior.
Batch callers continue to treat an empty chronological selection as all valid lines.

Capture returns `FinalState`, `Chronological`, or `Breakpoints`. Renderers consume these
results without invoking guest execution. The existing `DebugTraceHelper` and
`PersistentGuestSession` tracing methods are internal implementation entry points.

`SourceAnalysis.observations(declaringClass, methodName, argumentTypes, line)` returns
source observations for a captured location. Use `isFinal(variable)` and
`lambdaImplementation(variable)` on that result instead of reading the removed
`finalMethodVariables()` and `lambdaMethodAssignments()` maps. Supply runtime class
and argument type names. Source analysis owns method matching and lambda selection,
including the existing first-assignment fallback before any recorded assignment.
Unmatched methods return empty evidence. Static lambda lookup remains available.

`InputObservation` owns one execution's reader recognition, standard-input identity,
and logical cursor. Construct a fresh instance per job, register reader requests with
`registerRequests(vm, requests)`, call `start(vm)` before processing events, and pass
reader exits to `record(event)`. The guest owner retains request cleanup. Read
`consumed()` and `offset()` when capturing snapshots; offsets remain UTF-16 indices.
Missing reader metadata is tolerated, and a missing initial input identity is looked
up again when observing reads. `InputTracker` implements cursor tracking inside the internal trace package.
