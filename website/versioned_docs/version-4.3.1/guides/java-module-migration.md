# Internal tracing modules

This is contributor guidance for `cs1302.tracer.internal`, not a supported library
integration guide. These public implementation classes are annotated `INTERNAL`
and carry no external compatibility promise. Library consumers should use
[Java integration and 4.0 migration](../reference/java-api.md).

The following describes the current internal ownership and call conventions.

## Building internal values

Since 4.1.0, modern `Step`, Python Tutor `TraceStep`, and `BatchJobRequest` expose
nested builders and `toBuilder()`. These remain internal interfaces. An unchanged
copy preserves every field. Builders are mutable and reusable, and are not thread-safe.
Their collection setters and copy initialization detach immediate lists and maps,
preserving null containers, null entries, and iteration order. Nested frames, maps,
and arbitrary payload objects may remain shared. Existing constructors retain their
original collection ownership behavior; these records are not deeply immutable.

Step builders default input tracking to an empty consumed string and offset zero;
other numeric values start at zero and references at null. Explicit setters can
replace those values, including with null references. Serializers still supply
the actual event and source location, related global attributes and ordering, and
optional thread metadata. Builders do not interpret snapshots or infer rendering
fields. Python Tutor null globals and ordered maps remain valid; blanket
`Map.copyOf` is inappropriate for these payloads.

The batch-wire builder starts with all fields omitted, including nullable booleans,
limits, and breakpoints. It preserves omitted versus explicit empty selections and
does not apply Java request defaults. Null/blank breakpoint entries remain accepted
for the existing deferred parser to ignore. Gson decoding remains independent of
the builder, and caller-profile resolution retains its existing responsibility.

The supported `TraceOutcome` builder also accepts explicit data. Production
outcomes are assembled by `TraceRendering.freeze` from a single execution result;
it owns agreement between structured metadata, trace JSON, and envelope JSON.
Neither step builders nor outcome builders introduce another rendering policy.

Primitive trace values are immutable and can be shared. Since 4.1.0,
`TraceValue.Primitive.Boolean.TRUE` and `.FALSE` provide reusable Boolean values;
`HeapInspection.primitive` also returns these constants. `new TraceValue.Primitive.Boolean(value)`
remains available, with the same value-based record equality. The constants contain
no guest or job state and do not change either JSON format.

## Preparation and execution ownership

Internal tracing code uses the preparation module to keep compiled files, source
evidence, prepared binary dependencies, and archive views alive for the same scope:

```java
try (SourcePreparation prepared = SourcePreparation.prepare(
        source, inputPath, SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED)) {
    // Pass prepared to capture; render using prepared.submitted().
}
```

`SUBMITTED_ONLY` uses submitted sources without neighboring source discovery or a parser
search root. `SUBMITTED_OR_COMPILED` keeps submitted-only discovery while using the
private compiled-source directory for parser resolution; one-shot `Tracer.trace`
uses this policy under trusted inspection. `NEIGHBORS` discovers neighboring sources
and uses the discovered root for resolution. `NEIGHBORS_OR_COMPILED` also falls back to the prepared source directory when
no original root is available. These preserve the existing field-only, bounded CLI, and
ordinary CLI/batch preparation policies respectively.

The four-argument `prepare` overload accepts a `TraceClassPath`. It resolves the
ordered dependency closure once per job and optionally captures copies before
compilation. `PreparedClassPath` owns the effective locations and their correlated
version-selected archive views; archive acquisition occurs after selection and
copying, before source parsing and compilation in normal preparation. Compiler and
bytecode type analysis share those views; both guest modes use the same prepared
dependency order. The existing
overload uses an empty class path. See [binary dependencies](../reference/java-api.md#binary-dependencies)
for the supported Java interface.

Closing the preparation releases its compiled files, then closes the prepared
class path, which closes archive readers before removing owned dependency copies.
Caller-owned dependencies remain untouched.
Preparation failures release acquired resources before propagating the original
failure. The source evidence list is immutable; its JavaParser compilation units
remain mutable. Lazy type resolution must finish before the preparation closes.

The dependency-aware `CompilationHelper.compile` overload accepts a `PreparedClassPath`
instead of separate dependency-location and host binary-root lists. Internal callers
use this owner to keep both views of the ordered closure consistent; they do not
open or close `BinaryClassPath` themselves.

`FileSelection.sources(root, glob, followLinks)` and
`FileSelection.dependencies(root, glob, followLinks)` share one regular-file walk
with distinct path projection. Source matches retain a portable relative name and
a read path below the resolved root. Dependency matches use the original normalized
root alias, preserving manifest origins and lexical dependency identity. Matching
does not read file contents: `SourceBundle.Builder` captures source text atomically
when added, while `PreparedClassPath` selects dependency files when a job is prepared.
Dependency copying and owned-tree deletion remain with `ClassPathFiles`.

`CompilationHelper.compile`, `App.parseSource`, and `App.discoverAllCompilationUnits`
exist under `cs1302.tracer.internal`; `App` here means the internal command class,
not the supported launcher. `SourcePreparation.prepare` keeps internal callers from
having to coordinate compilation, source resolution, and cleanup themselves.

## Submitted source identity

`CompilationHelper.parseSources(code)` returns a `ParsedSources` whose parsed files
and detached `SubmittedSources` describe the same input. Its constructor is private;
callers cannot pair ASTs from one submission with a description from another.
Compilation and source analysis retain AST and type-resolution ownership.
`SubmittedSources` retains original code, exact ordered file contents, logical and
debug identities, and entry selection, without ASTs or compilation resources.
`SourcePreparation.submitted()` remains usable after preparation closes.

Normal `prepare` resolves dependencies before parsing. CLI `list-breakpoints` uses
`parseSources`, then `prepareParsed`, preserving its source-parse-first validation.
Listing reads each file's `logicalPath()` and `listingLines(availableLines)`;
the latter preserves exact logical/native lookup followed by the first matching
basename suffix when the exact line set is empty. It does not use breakpoint
specification matching.

Prepared tracing passes this description through `TraceRendering` and
`TraceCompletion`. Raw-source serializer overloads and `SourceMetadata.from(code)`
use the same parser and source identity implementation. They accept parseable
source without compiling it or requiring a launchable main method. With no main
candidate, source metadata retains the existing first-file entry fallback.
Only submitted files contribute output metadata; neighboring analysis evidence
does not become a submitted source.

`SubmittedSources.metadata()` projects package-relative debug identities into the
ordered source map. This is the point where duplicate debug paths are rejected,
even if their distinct logical compilation destinations were valid. Do not move
this validation into parsing or preparation. Keep metadata projection at each
rendering path's existing position so ambiguity errors retain their precedence
relative to step-conversion failures:

| Rendering path | Metadata projection |
| --- | --- |
| Python Tutor single snapshot | Before step conversion |
| Modern single snapshot | After step conversion |
| Either chronological sequence | After converting steps |
| Modern breakpoint map | After converting the map |
| Ordinary Python Tutor breakpoint map | Once, before iterating groups |

Each failed metadata projection creates a fresh exception. Recovery can retry
rendering and suppress its new failure onto the original one; reusing an earlier
exception would change that behavior.

## Execution and completion

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

`TraceCompletion` coordinates sequence payloads for envelope CLI and the Java/NDJSON
worker using three named operations:

- `envelope` accepts supplied snapshots or uses raw retained history when a guest
  was launched. Available empty history is renderable. Its `Envelope` result pairs
  payload and failure; a rendering `RuntimeException` replaces an earlier failure
  and changes the phase to `serialize`.
- `workerSuccess` chooses chronological capture, finalized multithread retained
  history, or targeted retained presentation, sets phase `serialize`, and renders.
  It propagates failure to the worker's existing recovery branch.
- `workerRecovery` renders only when raw retained history is available and nonempty,
  choosing chronological or targeted presentation. A rendering `RuntimeException`
  is suppressed onto the original failure without replacing its phase.

Call these operations at the existing adapter scopes: envelope completion follows
source-preparation cleanup, worker success runs inside preparation, and worker
recovery remains in its outer catch. Recovery may retry rendering after a success
render or cleanup failure. Keep final `TraceSession.result` construction after
cleanup and worker guest-health checks. Ordinary CLI continues to pass its capture
directly to `TraceRendering.ordinary`.

Envelope and worker adapters retain `prepared.submitted()` in a job-local variable
immediately after preparation succeeds, before capture or cleanup can fail. Pass
that detached description to completion; recovery must not reconstruct identity
from raw source or require a live preparation. A failed preparation has no completed
description or trace history to render.

For multithread worker success, completion requests the session's existing final-output
refresh before selecting retained states. Guest execution drains and detaches output
before reusable drainers reset; `SnapshotRetention` owns output projection and
accounting, and `TraceRendering` owns format conversion. Adapters retain guest
cleanup. Envelope uses raw
retained history for targeted jobs; worker completion uses targeted presentation.
The adapters retain their distinct exception scopes and interrupt handling.

## Snapshot assembly and reachable-heap inspection

`SnapshotCapture` owns snapshot assembly and all-thread type prepasses. It creates
one package-private `HeapInspection` with the suspended event thread and shared
`ReifiedTypeMetadata`. Stack locals, `this`, and statics contribute root evidence
and captured references through the existing capture logic; all threads feed the
same heap owner. Root insertion and budget-check ordering remain with capture.

`HeapInspection` owns JDI primitive and object conversion, the admitted-identity
set, FIFO queue, descendant discovery, source-lambda insertion, and heap map.
`encounter` deduplicates an identity before charging and retaining it, and keeps
that identity known after dequeue. `sourceLambda` admits the reference, charges
the source text, then installs its captured lambda; repeated occurrences still
charge and replace the text. `finish` traverses pending objects while preserving
already installed values, then transfers the existing completed map to capture.
Capture stops using that owner after success and discards it on failure.

Use this inspection interface for graph and failure tests; callers do not manage
its queue or partially filled heap. `TraceValue` contains captured values and
`toWrapperObject`, without JDI inspection or a descendant-list protocol.
Snapshot capture still samples input/output, performs allocation checks, and calls
`TraceSession.commit` only after the complete state exists. A failed inspection
does not publish a partial heap or replace previously retained history. Trusted
inspection retains its permitted guest calls and fallbacks; field-only and
multithread inspection invoke no guest methods.

## Source and input observations

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
