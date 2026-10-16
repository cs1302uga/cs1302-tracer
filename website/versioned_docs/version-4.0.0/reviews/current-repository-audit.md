# Current repository audit

Scope: the complete current repository at `8dfd93f`, plus the remediations below.
The review covers the supported Java API, tracing engine, tooling, documentation,
and build/CI integration. Output formats and committed trace fixtures must remain
compatible. Historical generated documentation and fixtures are checked through
sources, release validation, and regression checks rather than line-by-line prose
review. This audit is in progress.

## BR-001: one-shot requests discovered unsubmitted neighbors

**Medium; verified by execution; remediated.** `Tracer.trace` inherited the batch
worker's ambient source discovery. A consumer running in a directory containing
`ambient/Neighbor.java` could submit only `ambient/Main.java`, reference Neighbor,
and unexpectedly compile and run it. This contradicts the documented self-contained
source contract and makes the same request depend on the caller's working directory.
It is a correctness issue, not a claim that guest execution is a security sandbox.

The regression runs a real external API consumer in a temporary working directory.
Before the fix it completed; the expected result is a compilation failure with no
guest stdout. One-shot requests now select submitted sources and their private
compiled-source directory explicitly. The legacy CLI/batch discovery policy and
FIELDS inspection policy are preserved. A positive multi-file request also checks
that other submitted sources still compile and execute. No serializer or protocol
shape changes are involved.
