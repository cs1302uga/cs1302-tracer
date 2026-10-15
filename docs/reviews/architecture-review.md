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
