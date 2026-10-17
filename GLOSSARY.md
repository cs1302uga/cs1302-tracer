# Java execution tracing

The tracer describes guest Java execution as snapshots of stacks and reachable heap objects.

## Language

**Trace rendering**:
Conversion of completed snapshots into ordinary trace JSON, result envelopes, or batch responses.
It owns format-specific root shapes, presentation choices, and JSON encoding rules. Execution owns
status and diagnostics; batch processing owns correlation, response ordering, and writing.

**Snapshot capture**:
Assembly of one complete execution state from selected thread stacks, static fields, reachable heap
objects, source observations, reified type metadata, and input/output observations. It owns the
ordering of those observations and shared heap identity within the state. Session budgets and
snapshot retention govern admission; guest execution owns suspension, event loops, and cleanup.

**Trace-job resolution**:
Interpretation of a caller's requested settings under its caller profile to determine effective budgets, inspection, capture intent, source-discovery policy, and presentation choices. Resolution preserves the caller's validation timing and failure semantics; it is distinct from executing the job or rendering its output.

**Caller profile**:
The defaults and interpretation rules belonging to a tracing entry mode: one-shot Java, ordinary CLI, envelope CLI, or NDJSON batch. Explicit settings are interpreted under that profile; equivalent-looking inputs from different profiles need not mean the same thing.

**Capture intent**:
The execution states a job requests: final state, chronological states at all or selected locations, or selected-breakpoint states with their retention policy. Intent distinguishes an omitted selection from an explicitly empty selection according to the caller profile, without requiring breakpoint specifications to be validated earlier in the job lifecycle.

**Reified type metadata**:
Type information reconstructed for a captured execution state from guest runtime types and available source evidence, including generic arguments where inference supports them. It is a conservative description, not a guarantee that erased generic arguments can be recovered at runtime.
_Avoid_: Actual generic runtime type

**File selection**:
Identification of an ordered group of regular files using a portable root-relative
glob and an explicit symbolic-link policy. Selection owns traversal, matching, and
ordering by encountered relative paths. Source collection reads selected files
immediately; binary dependency preparation selects files for each job and preserves
the caller's encountered root for dependency identity. Selection does not capture
file contents or provide a simultaneous filesystem snapshot.

**Source bundle**:
An immutable, ordered collection of captured Java source text and logical relative
paths, represented using the tracer's existing delimiter format. Bundle construction
owns naming, structural validation, framing, and UTF-8 export. It captures individual
file inputs or explicit glob selections when added. A glob group is ordered by its
encountered root-relative paths and added atomically, without compilation or
dependency discovery. Self-contained here means
the captured text no longer depends on the original files, not that all program
dependencies are present or that the program is valid Java.

**Source preparation**:
The work that turns submitted Java sources and permitted dependencies into a compiled
guest program and the source evidence needed to trace it. Preparation owns its
temporary compilation files and one prepared class path until tracing finishes and
releases acquired resources if preparation fails. It closes compiled output before
releasing the prepared class path.

**Source-discovery policy**:
The caller's explicit choice of which sources may participate in preparation and which source-resolution fallbacks apply. Existing choices distinguish neighboring-source discovery from submitted sources only; they need not be identical for every execution path.

**Prepared class path**:
One job's ordered binary dependency locations after explicit selection, glob expansion,
duplicate handling, local manifest traversal, and optional copying. Compilation, binary
type analysis, and guest loading use the same prepared selection. Its owner keeps the
guest locations and host archive views correlated and closes archive readers before
removing copies; caller-owned inputs remain borrowed unless copying is requested.
This is distinct from the compiled output directory and from source-discovery policy.

**Source observations**:
Descriptions derived from source evidence for a captured location, including final-variable flags and lambda implementation text. Source analysis owns indexing, method matching, and location-sensitive selection of that evidence. These observations preserve existing inference behavior, including the first-assignment fallback when no lambda assignment precedes the captured line.

**Snapshot retention**:
Ownership of the captured execution history and its presentation rules, including replacement, accumulation, duplicate handling, and output projection. It also owns retained-byte accounting and admission of completed snapshots: rejecting a snapshot must not partially update the history or its accounting. Budgeted history remains distinct from format-specific presentation. Retention uses job output without taking ownership of its immutable bytes.

**Trace dispatch**:
Selection of the requested tracing mode and its execution through a dedicated or persistent guest. Mode selection is independent of output rendering; dedicated and persistent guests retain their distinct launch, termination, and reuse rules.

**Trace completion**:
Selection of completed or retained snapshots for an envelope CLI or Java/NDJSON worker
result payload, including partial history after failure, under the caller's completion
rules. It coordinates trace
rendering and rendering-failure precedence. Snapshot retention owns history and
output projection; guest execution owns output detachment and reuse; execution
owns result classification. Completion preserves the caller's resource scope and
the distinction between unavailable history and available empty history. Ordinary
CLI capture keeps its direct rendering path.

**Job output**:
The stdout and sanitized stderr belonging to one guest execution. Live prefixes may refer to reusable drainers; finished output is immutable, and retained snapshot prefixes must be detached before those drainers are reset.

**Input observation**:
The per-job interpretation of supported reader activity as consumption of standard input. It owns standard-input identity and fallback, logical read recognition, and the consumed-input cursor. Nested reads count once, unrelated streams do not count, and each job starts with a fresh cursor. Existing cursor-offset semantics are preserved; guest execution retains ownership of event loops and request cleanup.

## Documentation releases

**Documentation release selection**:
The decision about which documentation versions are available, which version is the default, and which source revision and routes belong to each version. Guides and Javadoc share this selection.

**Preview mode**:
An explicit documentation build mode that includes prepared snapshots and development documentation. An untagged snapshot matching the current project version, or its target after removing a trailing `-SNAPSHOT`, may use working sources for its Javadoc and is labeled unreleased.

**Published mode**:
An explicit documentation build mode that includes published stable releases with prepared snapshots, alongside development documentation. Release Javadoc requires the matching release tag; working sources cannot substitute for a missing tag.
