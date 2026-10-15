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
