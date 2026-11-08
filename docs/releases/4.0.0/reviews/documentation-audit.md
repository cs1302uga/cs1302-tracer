# Documentation release and debugger audit

> Historical review: findings, source locations, interface terminology, counts, and
> validation claims below apply to the stated revision scope, not automatically
> to the current checkout. Artifact paths identify local evidence from that run
> and may not exist in another checkout. For current supported behavior, see
> [Java integration](../reference/java-api.md) and
> [architecture](../contributing/architecture.md).

Scope: `a1bac6e..53e0a6d` and the remediations recorded here. Review covers
the major-version bump, Javadoc hosting, release selection and build manifests,
and debugger test cleanup. Serialized trace formats remain unchanged.

## DA-CQ-002 — Intentional preview interruption

Resolved `py/empty-except` and `githubsecuritylab/insufficient-logging` at
`scripts/serve_docs.py` as expected behavior. Only `KeyboardInterrupt` is caught:
Ctrl-C ends local preview normally. The surrounding HTTP-server and temporary-
directory context managers close the listening socket and remove the mount.
Operational exceptions still propagate. No security event or unexpected failure
is discarded. An explanatory comment now records this intent; adding a traceback
or security log for an ordinary local shutdown would not correct a defect.

Inspected both query definitions. The Community rule reports any `pass` or
`continue` in an exception handler without considering exception type or cleanup.
The official rule specifically requests an explanatory comment for an empty
handler. This resolution changes neither server behavior nor trace output.

## BR-001 — Keep build output and its manifest together

Verified a Medium-severity defect at the CLI boundary: forwarding Docusaurus
`--out-dir` produced a site outside `website/build` while the manifest copy still
used that fixed directory. A fresh build failed at the copy; an existing build
received a manifest belonging to the new output. Positional site roots and config
replacement could likewise bypass the wrapper's ownership.

Resolved by rejecting unsupported overrides before invoking any build tools or
writing manifests. Supported preview host/port and no-open flags, and build/start
no-minify, remain available and are documented. Five CLI tests use real temporary
projects, Git tags, and files, with stand-ins only for external Maven/npm execution.
Red-green checks prove early rejection and supported flag forwarding. Tests also
verify archived release sources, successful build provenance, preservation of
prior API output after partial generation failure, and no new completed-build
manifest when npm fails before changing output. They do not assert that Docusaurus
itself atomically preserves old output during a failed rebuild. A deliberate
working-source-for-release mutation was detected by the pinned-source test.

## DA-CQ-004 — Pin the release action

The JavaScript Community pack also scans Actions YAML and reported the existing
mutable `softprops/action-gh-release@v3` reference. Pinned it to the peeled commit
`efb35369e0ad2afab669f228072c1b0d510eae64`, verified directly with upstream
`git ls-remote` for `refs/tags/v3` and `refs/tags/v3^{}`. This preserves the action
implementation currently selected by v3 while preventing later tag movement from
changing release execution. A fresh JavaScript database and scan confirm the
unpinned-action warning is gone. No hosted release was executed.

## DA-CQ-003 — Expected command-line execution

Resolved the Python Community pack's 24 final `audit/command-line-injection`
records after inspecting every location and the query definition. This audit
query selects every command-execution sink without requiring input-to-sink flow;
its records are review prompts, not demonstrated injection vulnerabilities.

Javadoc generation invokes the repository's Maven wrapper and Git/tar through
argument arrays. Release versions must match the stable numeric version grammar;
archive revisions come from resolved Git commit IDs. Release metadata commands
use fixed Git/Node/npm programs and explicit arguments. The documentation wrapper
now validates its forwarded flags before generation. The rehearsal and test
helpers execute fixed tool commands in disposable projects; the new CLI tests
invoke their copied script with the current Python interpreter. The fixture
verifier intentionally permits the local caller to select a Java executable.
The executable-documentation check deliberately runs the checked-in quickstart
shell block; the source is trusted repository documentation, not remote input.
No reviewed caller crosses a privilege boundary with untrusted command strings.

The raw results and per-location dispositions remain available without suppression.
Reassess these resolutions if the tools are exposed to untrusted users through a
privileged wrapper. The project still requires isolation when executing untrusted
Java submissions, as described in the runner contract.

## Coverage and remaining quality fixes

`2913520` adds a missing-history regression at the agreed Project interface:
preview mode must reject an older snapshot without its release tag. A deliberate
mutation that allowed working-source fallback was detected. This closes a test gap;
it does not claim the original implementation was broken.

DA-CQ-001 removed two unused `json` imports identified by CodeQL. Python tests
passed afterward, and both alerts disappear from the fresh Python rescan. This
mechanical correction needed no artificial behavioral red test.

The five build-wrapper tests complement the existing real-build rehearsal. They
cover generation failures and pinned source ownership cheaply at the approved CLI
boundary. The complete Python suite now has 24 passing tests. Unit coverage alone
excludes the temporary copied CLI scripts and the existing full-build rehearsal;
no blanket Python or JavaScript coverage percentage is claimed.

## Final review and scan evidence

Balanced review inventoried all 41 original changed paths, traced the affected
Java functions/callers and documentation tooling, and checked the 18 copied
snapshot files against their sources. The second review covered the remediations
and found BR-001 resolved with no additional actionable findings. No serialized
Java output implementation or fixture expectations changed during this audit.

CodeQL 2.27.2 used fresh Java, Python, and JavaScript databases. Java compilation
was traced with Homebrew JDK 25 targeting release 21; Python and JavaScript were
re-extracted after their fixes. Database quality passed with zero extractor errors.
The archives match all 53 production Java, 13 Python, and five first-party JS/JSX
sources byte-for-byte. Generated and vendor directories were excluded. Actions
YAML was also extracted by the interpreted-language databases.

Explicit verified suites resolved 387 Java, 233 Python, and 245 JavaScript queries:

- Official Java 1.11.12, Python 1.8.12, JavaScript 2.4.7 security-and-quality plus
  experimental suites.
- GitHub Security Lab 0.8.2 query packs for all three languages, matching installed
  Java/Python model packs, and Trail of Bits Java 0.3.0.
- Default remote plus local sources; the previously verified Picocli/JDI Java
  project model. JavaScript source/sink diagnostics and source inspection found
  no additional project-specific model gap.

Final unfiltered results contain 172 Java records (53 previously resolved alerts
matching prior query/source statements and 119 diagnostic records), 77 Python
records (24 reviewed command audit prompts, one intentional-interruption warning,
52 diagnostics), and two JavaScript diagnostic sink records. The Java dispositions
remain CQ-002 through CQ-006 in [the architecture review](architecture-review.md).
The JavaScript sink records describe the landing-page redirect and link, both
constructed from the validated build manifest. No actionable findings remain;
these counts are not a claim of zero scanner records or proof of security.

Artifacts are retained locally under `static_analysis_codeql_1/`, including initial
and final databases, exact commands, suite/model selections, quality gates,
source-match checks, raw/final SARIF, per-location triage, and red-green/mutation
logs. They are ignored locally and are not committed.

## Validation and limits

The fresh Java 25 package build passed. All 24 committed trace fixtures passed
unchanged on both Java 21 and Java 25; executable documentation checks passed.
The complete documentation wrapper rebuilt seven API-reference versions, and
link/fragment validation passed across 1,532 rendered pages. Release metadata
validation confirms the pending 4.0.0 snapshot matches current documentation.

Each remediation uses the normal staged-file Checkstyle, warnings-as-errors,
750-test, and 100% production line/branch coverage hooks. One unused-import commit
attempt passed all tests but missed the line-coverage gate. The hook deletes its
scratch report, so the exact missed line was unavailable. An isolated retained
rerun passed with no missed lines or branches, as did the unmodified retry and
subsequent hooks. No Java source, assertion, threshold, or hook was changed to
obtain that result; the intermittent failure is retained in the audit logs.

The tests establish local CLI/build behavior. No hosted deployment, release,
Linux execution, or untrusted-guest isolation was exercised in this audit.
