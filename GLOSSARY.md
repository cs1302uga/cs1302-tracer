# Java execution tracing

The tracer describes guest Java execution as snapshots of stacks and reachable heap objects.

## Language

**Reified type metadata**:
Type information reconstructed for a captured execution state from guest runtime types and available source evidence, including generic arguments where inference supports them. It is a conservative description, not a guarantee that erased generic arguments can be recovered at runtime.
_Avoid_: Actual generic runtime type

**Job output**:
The stdout and sanitized stderr belonging to one guest execution. Live prefixes may refer to reusable drainers; finished output is immutable, and retained snapshot prefixes must be detached before those drainers are reset.
