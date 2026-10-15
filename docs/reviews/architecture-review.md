# Architecture review and remediation

Scope: all 141 paths in baseline `0e82ee0`, compared with `d559faa`, plus subsequent
remediations. Both serialized trace formats must remain compatible with the existing
24 regression fixtures. Review uses balanced-code-review and CodeQL. Tests use the
agreed CLI/batch, JobOutput, and ReifiedTypeMetadata public interfaces.

## COV-001 — Frozen stderr after drainer reuse

Resolved by extending the consecutive-job test to read stderr through `JobOutput`'s
public interface after the underlying drainer has been reset. The expected result is
the literal original guest output, `warning`. Removed the test's dependency on the
package-private prefix capture/accounting representation.

Validation: `./mvnw -B -ntp test -Dtest=JobOutputTest` passed (2 tests). This closes
an existing coverage gap; it does not claim that the original behavior was broken.
The baseline's strict pre-commit coverage check failed with two uncovered branches.
The user authorized bypassing that hook for baseline/intermediate coverage commits
only; full enforcement resumes after both gaps are addressed.

## COV-002 — Unreachable receiver-cache equality branch

Resolved by tracking receiver identities rather than duplicating their type strings.
`recordFallback` calls `update` only for an absent identity and a non-null type;
`refine` calls it only after `isMoreSpecific` rejects equal types. `update` then
synchronously notifies every registered context. Therefore a notified receiver's
cached type cannot equal its new retained type. Removed that redundant comparison
rather than reaching into private state to force an impossible coverage branch.

Existing public frame-binding tests exercise equal refinements, unrelated receivers,
accepted refinements across multiple contexts, and rejected conflicting evidence.
The normal commit hook validates the full production coverage gate for this change.

## CQ-001 — Unused resolver parameters

CodeQL reported two `java/unused-parameter` results in `appendStackField` and
`extractSnapshotFields`. Verified by inspecting both complete private methods:
metadata refinement now belongs to `ReifiedTypeMetadata`; neither method reads its
resolver argument. Removed both unused parameters, their call arguments, and stale
Javadoc. Updated existing reflective tests to the new signatures without adding
private-method tests or changing their behavioral assertions.

This is a refactoring correction, not an observed behavioral bug, so no artificial
red test was introduced. The focused metadata/debugger tests and the normal full
coverage commit hook validate preserved behavior. Serialized formats are unchanged.

## CQ-002 — Long output targets compared with retained byte counts

Resolved `java/comparison-with-wider-type` in `StreamDrainer.syncUntil` as an
intentional comparison, verified by static inspection and the existing
`observesLongByteTargetsWithoutNarrowing` regression test. Retained buffer sizes are
ints, while guest byte counters and job-relative targets are longs. Java widens the
size before comparison; there is no narrowing or arithmetic overflow in that
comparison. A target above the buffer's representable size remains unmet, and the
loop still exits on timeout, closure, EOF, interruption, or a stopped session.
Narrowing the target would incorrectly treat some large targets as already met.
No code change or suppression is needed.

## CQ-003 — Ignored worker-pool offer results

Resolved both `java/ignored-error-status-of-call` results in `BatchTraceService`
by static proof of the pool's ownership invariant. The private queue is constructed
as an unbounded `LinkedBlockingQueue` (capacity `Integer.MAX_VALUE`). Initialization
adds each of `workerCount` distinct workers once. Execution takes one worker and
returns it once in `finally`, only after a successful take. No other path inserts
workers. Before either offer the queue contains at most `workerCount - 1` entries,
which is strictly below capacity even for the largest positive int worker count.
Consequently these calls cannot return false for capacity exhaustion. Existing
batch scheduling, interruption, and reuse tests cover the surrounding behavior.
No code change or suppression is needed.

## CQ-004 — Unread switch-pattern bindings

Resolved 34 `java/local-variable-is-never-read` results as intentional unused
bindings required by this project's non-preview Java 21 target. They are type
patterns in exhaustive switches in `PyTutorSerializer`, `DebugTraceHelper`, and
`PersistentGuestSession`: the selected runtime type determines the constant
serialized type/value or termination action; the bound value is unnecessary.
All 34 locations were inspected. Replacing their identifiers with unnamed patterns
would require preview features or a newer release, and converting exhaustive
switches to conditional chains would add complexity without fixing a defect.
Existing serializer and event-processing tests exercise their behavior. No code
change or suppression is needed.

## CQ-005 — Caller-selected CLI input paths

Resolved 15 path-input results (five each from `java/path-injection`,
`java/file-path-injection`, and `githubsecuritylab/java/file-path-injection`) as
intentional CLI capabilities. The five sinks are `App` reads at lines 168, 215,
320, 471, and 702: adjacent source discovery, source input, guest stdin input,
bounded source input, and batch NDJSON input. Inspected the source-to-sink paths:
all originate in the caller's Picocli `--input` or `--stdin-file` options.

The [tracing guide](../guides/tracing.md) explicitly permits source-root discovery
and stdin files. The [runner contract](../RUNNER_CONTRACT.md) requires external
filesystem confinement for hosted untrusted submissions; the local CLI does not
promise to restrict the invoking user's file access. These reads cross no such
boundary in the supported direct CLI use. Streamed source destination validation
remains separate: `CompilationHelper.writeSourceFiles` normalizes destinations,
checks containment in its temporary directory, and uses `CREATE_NEW`.

This revalidates the existing [security analysis](../contributing/security-analysis.md)
disposition for the current code and the newly verified Picocli model. No path
allowlist, output change, or alert suppression was introduced. Reassess this
resolution if a wrapper accepts caller-controlled CLI options across a privilege
boundary without the required isolation.

## CQ-006 — JDI command-line construction

Resolved `java/concatenated-command-line` at `GuestLauncher.java:48` by inspecting
the complete launch path and its existing integration regression. JDI requires an
options string and tokenizes it itself; the launcher does not invoke a shell. It
chooses a quote character absent from the classpath, main class, and Java home,
configures that delimiter on the connector, and surrounds the literal classpath
with it. The remaining options are constants. CLI main names come from compiled
Java declarations; the persistent harness name is a class constant. Library-created
compilation results are caller-owned inputs, not a sandbox boundary.

`GuestLaunchPathTest.launchesFromAClasspathContainingSpacesAndQuotes` relocates
compiled code to a directory containing spaces, both ASCII quote characters, and
the launcher's initial private-use delimiter, then verifies a successful trace.
The full Java 21 and Java 25 test runs pass this regression. No code change or
suppression is needed. This confirms the existing security-analysis disposition.

## Final review and scan evidence

Balanced review covered the baseline and subsequent remediations, with particular
attention to snapshot-owned type evidence, cache invalidation, per-job output
freezing, batch reuse, limits, and both serializers. Token comparison identified
104 Java files with only mechanical changes; behavioral changes and their callers
were inspected separately. Re-review of the parameter removal and coverage changes
found no additional actionable defects. No High or Medium findings remain.

CodeQL 2.27.2 analyzed a fresh traced compilation of all 50 production Java files.
The final database source archive was compared byte-for-byte with the working tree;
its production source matches commit `6f17548`. Database quality passed with zero
extractor errors, and the explicit suite guard resolved 387 queries:

- Official `codeql/java-queries@1.11.12`, security-and-quality plus experimental.
- Installed `trailofbits/java-queries@0.3.0`.
- Installed `githubsecuritylab/codeql-java-queries@0.8.2`, with its matching model pack.
- Default remote sources plus local sources.

The project model covers only verified gaps: diagnostics identify 90 Picocli option
field reads and three JDI argument setters. Baseline source/sink diagnostics lacked
these boundaries. The Picocli model adds 15 path alerts; the JDI model adds one
command-concatenation alert and six diagnostic records. A cached initial rerun was
not used to assess model impact: explicit `--rerun` and fresh-database analysis
confirmed the differences. The two unused-parameter warnings disappear after the
remediation.

The final unfiltered SARIF contains **172 warning-level records**: **53 alerts
resolved by CQ-002 through CQ-006**, and **119 Community debugging records**.
The debugging queries enumerate sources, sinks, and incomplete exploration paths;
they do not establish vulnerabilities. Their query definitions were inspected.
No results were deleted or suppressed. This is zero unresolved reviewed findings,
not a claim of zero raw alerts or proof of security.

Artifacts are retained locally in `target/static_analysis_codeql_1/`: `final.db`,
`raw/run-all.qls`, `extensions/`, `diagnostics/`, build and analysis logs,
`rulesets.txt`, `raw/results.sarif`, the identical `results/results.sarif`, and
`results/triage.json`. They were kept outside `target` during clean builds to avoid
deletion. Generated databases and SARIF are not committed.

Validation on macOS:

- Java 25: 736 tests passed; 100% production line and branch coverage; Checkstyle
  and warnings-as-errors compilation passed in the normal commit hook.
- Java 21: 736 tests discovered, one Java 25-only IO test skipped, no failures;
  the same strict production coverage gate passed.
- All 24 committed trace compatibility fixtures passed on Java 21 and Java 25
  after the production fix.
  The fixture normalizer's five tests passed. No output format changed.
- Documentation checks, eight documentation-tool tests, the documentation site
  build, and validation of links/fragments in 87 rendered pages passed.

The focused 26-test metadata/debugger run passed its tests but could not satisfy
whole-package coverage by itself; full isolated hook runs supplied that evidence.
One overlapping hook run observed another run deleting a shared temporary directory
and failed the legacy cleanup test. Serial execution resolved the interference;
no assertions were weakened and no later hook was bypassed. Existing reflection-
based tests remain; new coverage uses the agreed public output interface. These
changes were refactoring/coverage corrections, so no behavioral red test was
fabricated. Linux execution and hosted isolation were not validated here.
