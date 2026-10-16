# Current repository audit

Scope: the complete current repository at `8dfd93f`, plus the remediations below.
The review covers the supported Java API, tracing engine, tooling, documentation,
and build/CI integration. Output formats and committed trace fixtures must remain
compatible. Historical generated documentation and fixtures are checked through
sources, release validation, and regression checks rather than line-by-line prose
review. The final review found no remaining actionable findings after the five
remediations below.

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

## BR-002: constructor documentation promised nonexistent clamping

**Low; verified by code inspection; remediated.** `BatchTracer` documented that
numeric settings below one were clamped. Its constructor delegates to
`BatchTraceService`, which rejects such values before allocating workers, and the
CLI does not clamp them either. The Javadoc now requires positive settings and
documents `IllegalArgumentException`. A public-API test checks zero and negative
values for each numeric argument. This corrects documentation and locks in existing
behavior; it does not change argument handling or output formats.

## BR-003: valid non-finite floating-point values failed tracing

**Medium; verified by execution; remediated.** A guest declaring `Float.NaN`, positive and
negative float infinity, and `Double.NaN` completed its output but returned
`tracer_error` in both formats. The internal snapshot byte counter used a Gson
configuration that rejected non-finite values before output conversion. In
addition, the modern serializer returned raw non-finite floats, unlike its
existing string representation for non-finite doubles.

The public API regression checks successful completion and the actual named local
values in both serialized formats. The internal accounting sink accepts special
floating-point values; it never emits consumer JSON. Modern non-finite floats use
the existing special-double string convention, while finite floats and all Python
Tutor encodings retain their existing representations. No fields or protocol
structures are added or removed.

## BR-004: concurrent lambda rendering shared a mutable parser

**Medium; verified by execution; remediated.** Source analysis rendered lambda
implementations through a static `JavaParser` instance. The installed parser
reuses mutable parser state, so independent batch workers could interfere. A
public `BatchTracer` regression with four workers, sixteen jobs, and twenty-four
lambda fields per job reproduced a failed request with `AssertionError: A reference
was unexpectedly null` before the fix.

Each lambda-rendering call now creates its own parser. The regression verifies
that every job completes, preserves stdout, retains all twenty-four lambda
implementations, and associates distinct implementations with the correct fields.
The same test passes after the change. No wire representation changes are made.

## BR-005: security guidance described the former exception boundary

**Low; verified by code inspection; remediated.** The security-analysis guide
said library callers receive `NestingException`, although the supported facade
returns tracing failures through `TraceOutcome` and discovery failures through
`BreakpointDiscovery.diagnostics`. Internal helpers still throw the exception.
The guide now distinguishes those boundaries and identifies the legacy delimiter
matcher as internal. This is a documentation correction; runtime behavior and
internal regression tests remain unchanged.

## Scan configuration and extraction

The approved scan plan used CodeQL CLI 2.27.2 and fresh Java, Python, and
JavaScript databases. Java extraction traced a direct Homebrew arm64 `javac`
compilation with `--release 21`, avoiding the macOS system-tool tracing issue and
cached Maven classes. Maven compilation and annotation processing were separately
validated by normal build hooks. The Java database was rebuilt and rescanned after
each production remediation round. Unchanged Python/JavaScript databases remained
byte-for-byte current.

Explicit, verified suites selected 387 Java, 233 Python, and 245 JavaScript queries:

- Official security-and-quality plus security-experimental suites: Java 1.11.12,
  Python 1.8.12, JavaScript 2.4.7.
- Installed GitHub Security Lab 0.8.2 query packs for all three languages and
  Trail of Bits Java 0.3.0; installed Java/Python extension packs at 0.8.2.
- Default remote sources plus local sources and `tracer/input-models` 0.0.1.
  Picocli option-field flows and JDI connector argument sinks remain visible in
  the results. Their existing model still applies after package relocation; no
  demonstrated gap required another project model.

Database-quality and suite-validation checks passed. Source archive checks cover
all 61 Java implementation/API source files (all 72 production files, including
11 package documentation files, were passed to javac), all 13 tracked Python
files, all five current JavaScript/JSX files, and all three workflows. Generated
output, dependencies, previous analysis artifacts, and historical documentation
snapshots were excluded from interpreted-language extraction.

## Revalidated earlier CodeQL dispositions

No new substantive query/source-statement pair appeared. Raw SARIF remains
unfiltered; diagnostic records are distinguished from vulnerability findings.

| Language | Raw records | Substantive alerts or audit prompts | Diagnostics |
| --- | ---: | ---: | ---: |
| Java | 181 | 53 | 128 |
| Python | 77 | 25 | 52 |
| JavaScript | 2 | 0 | 2 |

Existing resolutions were checked against current callers and guards, not merely
matched by rule name. Their original separate resolution commits remain in
history; no new suppressions were added.

- **CQ-002:** the retained output buffer has an int-sized length, but guest byte
  counts are long. The comparison intentionally widens the retained count, with
  EOF, cancellation, and deadline handling for unretained output.
- **CQ-003:** both worker returns use an unbounded queue. Each checked-out worker
  is returned once, so ignoring `offer`'s boolean does not discard a worker.
- **CQ-004:** the 34 unused switch bindings are required named type patterns for
  the supported non-preview Java 21 compilation mode.
- **CQ-005:** the 15 path alerts describe five caller-selected source/stdin/batch
  read sites across three rules. These local CLI paths are not an authorization
  boundary. Submitted compiler paths still receive normalization, containment,
  duplicate-path, and exclusive-creation checks. Hosted execution requires the
  separate isolation specified in the runner contract.
- **CQ-006:** JDI requires a command argument string. The launcher chooses a quote
  delimiter absent from its literal arguments and does not invoke a shell; path
  regression tests exercise spaces and quotes.
- **DA-CQ-002:** `KeyboardInterrupt` intentionally ends local preview serving;
  the server context closes resources without logging an ordinary Ctrl-C exit.
- **DA-CQ-003:** the 24 Python command audit prompts enumerate sinks without
  establishing an untrusted flow. Calls use argument arrays; release version and
  pinned commit validation, controlled working directories, and the trusted
  repository/tooling boundary still apply.
- **DA-CQ-004:** the release action remains pinned to the previously verified
  commit. JavaScript sink diagnostics refer to the redirect and link constructed
  from the validated release manifest.

See the earlier [architecture review](architecture-review.md) and
[documentation audit](documentation-audit.md) for the original resolutions.

## Validation

The final Java source/test changes pass the normal staged-file hooks with
100% production line and branch coverage on both Java 21 and Java 25. Java 25
passes all 772 tests. Java 21 passes 771 and skips the existing
`countsRepeatedIoLinesOncePerPublicRead` test, whose explicit Java 25 guard covers
`java.lang.IO`; that test passes on Java 25. Neither run reports a failure or
error. Checkstyle and compiler warnings-as-errors remain enabled.

All 24 committed trace fixtures pass unchanged on both Java 21 and Java 25 after
the final capture change. The five fixture-verifier tests and all 24 Python tooling
tests pass. Executable documentation checks pass for CLI help, quickstart, JSON
examples, and the batch request. The pending 4.0.0 snapshot matches current docs.
The wrapper rebuilt seven API references, and rendered link/fragment validation
passed across 1,156 pages. ShellCheck, Bash syntax, and offline uv/uvx dispatch from
the root and scripts directory also pass.

The Javadoc example correction was committed first and separately. New findings
were corrected in separate commits, followed by review and affected scans. The
CodeQL records above retain their reviewed dispositions; zero actionable findings
does not mean zero raw records or establish proof of security.

No hosted deployment, release, Linux/Windows execution, or untrusted-guest
isolation was exercised locally. Historical documentation, generated/vendor
content, and lockfile contents were reviewed through their sources, configuration,
and validation behavior rather than manually auditing every generated line.

## Review coverage and evidence

Manual review followed the public API and CLI through compilation, source/type
analysis, dedicated and persistent guests, per-job output, retention budgets,
batch scheduling, and both serializers. It also covered Python release/site
ownership, shell wrapper behavior, website routing, Maven/assembly configuration,
and CI trust boundaries. Tests were reviewed by behavior and relevant assertions;
this is not a claim that every historical test or generated document received an
independent line-by-line review. Existing tooling tests exercise real temporary
projects and Git history, with stand-ins only for external Maven/npm commands.
No arbitrary tooling coverage percentage was introduced.

Evidence is retained locally in ignored `static_analysis_codeql_2/`: initial and
subsequent databases, exact commands, suite/model selections, source comparisons,
raw SARIF, per-location dispositions, and regression/build logs. The new behavior
tests use supported public API seams. The source-discovery, non-finite-value, and
concurrent-lambda regressions each failed before their fixes and passed afterward.
The constructor regression records existing validation behavior while its incorrect
Javadoc is corrected. Coverage gates and committed trace expectations are unchanged.
