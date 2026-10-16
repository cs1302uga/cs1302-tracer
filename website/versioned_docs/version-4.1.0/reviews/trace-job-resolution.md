# Trace-job resolution review — October 2026

> Historical review: this records the refactor from baseline `3d43db0` through `fc0b82c` and its
> validation on October 16, 2026. Counts, locations, and local artifact paths refer
> to this audit, not automatically to later revisions. See the current
> [architecture guide](../contributing/architecture.md) for implemented ownership.

## Scope and outcome

The balanced review examined the complete refactor diff, the new resolution and
contract tests, Java/NDJSON/ordinary CLI/envelope CLI callers, source preparation,
dispatch, session retention, guest lifecycle, and serialization boundaries.
The repository-wide follow-up rechecked the earlier
[current-repository audit](current-repository-audit.md) resolutions against current
code and tests, and scanned current Java, Python, JavaScript, and workflow sources.
Unchanged source was reviewed at affected contracts and recorded findings, rather
than represented as a new line-by-line review of every repository file.

No new actionable findings remained. Supported Java declarations and serialized
output formats are unchanged. No committed trace fixture was regenerated.
The earlier AGENTS.md and scripts/README.md edits were inspected but are separate
working-tree changes, outside the refactor commit.

## Coverage and behavioral checks

Characterization tests passed before the implementation changed: Java and NDJSON
compile before validating breakpoint text, while ordinary and envelope CLI reject
invalid breakpoint text before attempting to open a missing input file. The same
six checks pass after migration. Correlation, phase, stop reason, CLI exit status,
and diagnostics are asserted through supported entry points and real processes.

Resolution tests cover caller-specific omitted/empty selection, source policies,
default and explicit budgets, effective multithread settings, retained history,
presentation flags, detached selections, and deferred validation. Existing real
guest tests cover rendering, reusable-worker state, multithread capture, failures,
limits, and cleanup. An initial coverage run exposed one uncovered branch in the
new command-inspection guard; a direct contract test now exercises envelope
inspection acceptance. No coverage thresholds or exclusions were relaxed.

The Java 25 strict run passed 793 tests with 5,066/5,066 production lines and
3,121/3,121 production branches covered. Checkstyle and compiler warning checks
passed. The clean staged-index Java 21 commit hooks ran 793 tests: 792 passed and the Java 25-only IO test
was skipped; the strict 100% production line/branch gate passed. That IO test
passed in the Java 25 run.

Compatibility checks passed: all 24 committed trace cases across both formats,
five fixture-verifier tests, 24 Python tooling tests, executable documentation
links/help/quickstarts/JSON/batch checks, pending-release snapshot agreement, and
the Javadoc build. The generated consumer overview still documents self-contained
source, finite defaults, detached outcomes, and resource ownership accurately.
No formatter/protocol changes or expected-output updates were needed.

## Fresh CodeQL analysis

CodeQL 2.27.2 built fresh databases under local ignored
`static_analysis_codeql_3/`. Java extraction traced Homebrew arm64 javac with
`--release 21`, using fresh class output and the existing macOS tracing workaround.
Database quality checks passed for all three languages, with zero extraction
errors. Archived sources match all 62 current Java implementation/API files,
13 tracked Python files, five current JavaScript/JSX files, and three workflows.
Generated/vendor content was excluded from interpreted-language extraction.

Explicit suite validation passed for 387 Java, 233 Python, and 245 JavaScript
queries. The approved selection included official security-and-quality and
experimental suites (Java 1.11.12, Python 1.8.12, JavaScript 2.4.7), installed GitHub
Security Lab 0.8.2 query packs for all three languages, Trail of Bits Java 0.3.0,
and GitHub Security Lab Java/Python extension packs 0.8.2. Default remote sources
and additional local sources were enabled. The existing `tracer/input-models`
0.0.1 Picocli option-source/JDI argument-sink model still matches the current
boundaries and remains visible in results. No verified gap required a new model.

| Language | All records | Substantive alerts | Diagnostic records |
| --- | ---: | ---: | ---: |
| Java | 181 | 53 | 128 |
| Python | 77 | 25 | 52 |
| JavaScript | 2 | 0 | 2 |

Substantive rule/source matches are unchanged from the previous final audit.
Python diagnostic message ordering changed, without new sources or alerts.
The JavaScript zero-substantive-alert result was checked against extraction,
query counts, and the validated-manifest navigation data flow.

## Revalidated resolutions

The original separate remediation/resolution commits remain in history. No new
suppression was added, and no previously resolved item required reopening:

- CQ-002: the output buffer's int-sized retained count intentionally compares with
  a long guest byte count; EOF, deadline, and cancellation bound the wait.
- CQ-003: each checked-out worker returns once to an unbounded queue; ignored
  `offer` results do not discard workers.
- CQ-004: the 34 unused switch bindings are required by non-preview Java 21 type
  patterns. The Java 21 build remains part of compatibility validation.
- CQ-005: 15 path alerts describe five caller-selected local read sites under
  three rules. CLI paths are intentional inputs. Submitted compilation paths
  retain normalized containment, duplicate checks, and exclusive file creation.
- CQ-006: JDI argument construction uses a quote delimiter absent from its literal
  arguments and launches without a shell. Existing spaces/quotes regressions run.
- DA-CQ-002: Ctrl-C is an intentional quiet shutdown of the local preview server;
  context managers close both server and temporary mount.
- DA-CQ-003: 24 Python command-audit prompts enumerate subprocess sinks. Argument
  arrays, validated versions/commits, controlled working directories, and the
  trusted repository/tooling boundary still apply.
- DA-CQ-004: workflow release-action provenance is unchanged. JavaScript navigation
  destinations come from the validated release manifest.

The earlier BR-001 through BR-005 fixes remain present and their regressions pass:
self-contained Java sources, accurate constructor validation documentation,
non-finite numeric serialization, per-call lambda parsers, and supported error
boundary documentation. Guest execution remains subject to the separate
[runner contract](../RUNNER_CONTRACT.md); this audit does not certify a sandbox.

Local evidence includes `characterization.log`, `coverage-final.log`, language
`quality.log` and `suite-validation.log` files, unfiltered SARIF, comparison JSON,
and compatibility/build logs under `static_analysis_codeql_3/`. Those artifacts
are not committed and need not exist in another checkout.
