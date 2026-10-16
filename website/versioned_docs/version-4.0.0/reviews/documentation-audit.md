# Documentation release and debugger audit

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
