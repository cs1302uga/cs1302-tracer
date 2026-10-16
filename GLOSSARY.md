# Java execution tracing

The tracer describes guest Java execution as snapshots of stacks and reachable heap objects.

## Language

**Reified type metadata**:
Type information reconstructed for a captured execution state from guest runtime types and available source evidence, including generic arguments where inference supports them. It is a conservative description, not a guarantee that erased generic arguments can be recovered at runtime.
_Avoid_: Actual generic runtime type

**Source preparation**:
The work that turns submitted Java sources and permitted dependencies into a compiled guest program and the source evidence needed to trace it. Preparation owns its temporary compilation files until tracing finishes and releases them if preparation fails.

**Source-discovery policy**:
The caller's explicit choice of which sources may participate in preparation and which source-resolution fallbacks apply. Existing choices distinguish neighboring-source discovery from submitted sources only; they need not be identical for every execution path.

**Source observations**:
Descriptions derived from source evidence for a captured location, including final-variable flags and lambda implementation text. Source analysis owns indexing, method matching, and location-sensitive selection of that evidence. These observations preserve existing inference behavior, including the first-assignment fallback when no lambda assignment precedes the captured line.

**Snapshot retention**:
Ownership of the captured execution history and its presentation rules, including replacement, accumulation, duplicate handling, and output projection. It also owns retained-byte accounting and admission of completed snapshots: rejecting a snapshot must not partially update the history or its accounting. Budgeted history remains distinct from format-specific presentation. Retention uses job output without taking ownership of its immutable bytes.

**Trace dispatch**:
Selection of the requested tracing mode and its execution through a dedicated or persistent guest. Mode selection is independent of output rendering; dedicated and persistent guests retain their distinct launch, termination, and reuse rules.

**Job output**:
The stdout and sanitized stderr belonging to one guest execution. Live prefixes may refer to reusable drainers; finished output is immutable, and retained snapshot prefixes must be detached before those drainers are reset.
