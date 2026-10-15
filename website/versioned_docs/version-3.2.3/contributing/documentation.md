# Documentation maintenance and releases

README is the project introduction and quickstart; HACKING is the contributor
entry point. Detailed content lives in `docs/` and is rendered directly by
Docusaurus. Keep examples and links useful when reading Markdown on GitHub too.

## Local checks

Use Node.js 22 or later (CI uses 22), Python 3.9 or later, and a full JDK. From the repository root:

```sh
./mvnw -B -ntp package -DskipTests
npm --prefix website ci
python3 scripts/check_docs.py
python3 scripts/docs_release.py check
python3 -m unittest discover -s scripts -p 'test_*.py'
npm --prefix website run build
python3 scripts/check_site.py
```

Preview with `npm --prefix website start`, or serve the production build with
`npm --prefix website run serve`. Open the printed URL under `/cs1302-tracer/`.

`check_docs.py` compares CLI help to the executable, runs the marked quickstart
blocks, checks Markdown file destinations, compares both JSON examples after
normalizing heap identities, and runs the documented batch request. Refresh help
using `python3 scripts/check_docs.py --update-cli`; review the resulting diff.
When behavior changes, update JSON examples intentionally and review the contract.
The site build and `check_site.py` check rendered internal links, fragments, and
project-base-path handling. Prose accuracy still requires review.

The scheduled External documentation links workflow checks remote links and
uploads a report. It is not a required PR check because remote availability is
outside this repository's control. A newly configured site can return 404 until
its first deployment.

## Prepare a stable release

Versioned documentation begins with the first release using this workflow.
Historical software releases do not acquire snapshots retroactively. The preparation
command rejects an already-tagged version. Do not snapshot the existing 3.1.2 release.

1. Fetch release tags with `git fetch --tags`. Set the intended **new**, stable `x.y.z` version in `pom.xml` and finish the docs.
2. Build the JAR, regenerate CLI help if necessary, and run documentation checks.
3. Run `npm --prefix website run release:prepare`. This snapshots `docs/` and the
   sidebar using the Maven version. Review and commit `website/versions.json`,
   `website/versioned_docs/`, and `website/versioned_sidebars/` with the release changes.
4. Merge the release preparation into `main` before tagging. Never remove earlier
   snapshots. If preparation changes, edit the pending snapshot to match `docs/`
   and run `python3 scripts/docs_release.py check --tag vX.Y.Z` again.
5. Push a tag `vX.Y.Z` (or `X.Y.Z`) at that merged commit. CI requires exact agreement
   among the tag, Maven version, current docs, snapshot, and sidebar before the JAR
   release job can run. This pipeline handles stable releases only.

Before the first snapshot, all pages are development documentation. Local builds
show all prepared snapshots so reviewers can inspect them. Deployment obtains
published, non-prerelease GitHub releases and filters snapshots against that list.
A prepared but unpublished snapshot therefore never becomes the public default.
The highest published semantic version becomes the default, even if an older
release is published later. Each release keeps its version URL; development uses
`/next/` after the first documented release.

## Publishing and retrying

The Deploy documentation workflow runs after successful push runs of CI / Release,
including tag releases, or through **Run workflow** on `main`. It checks out the
latest `main`, verifies docs again, builds the complete site, uploads a Pages
artifact, and deploys using the `github-pages` environment. The workflow is
serialized with cancellation disabled. Every run rebuilds the latest state so a
retry of an older release cannot replace newer docs with an old checkout.

GitHub releases created by the CI token do not need to trigger another release
event: deployment uses `workflow_run` after CI / Release completes. The software
release needs documentation validation, but does not depend on Pages deployment.
For an outage, rerun Deploy documentation or dispatch it on `main`; do not recreate
the tag or software release. Build artifacts are also downloadable from PR checks
for local review; PRs do not deploy publicly.

Release snapshots are retained in Git. Correct an older release's documentation by
editing its snapshot in a reviewed PR; do not describe unreleased behavior there.
For a broken site deployment, revert the responsible docs/configuration change on
`main` and dispatch deployment again. Deleting a published release removes it from
the next public version selection, but does not delete its source snapshot.

## Release rehearsal

Run `python3 scripts/rehearse_docs_release.py` after installing site dependencies.
It creates two artificial versions in a disposable tree, builds the site before
and after publishing the second version, checks stable routing and retained
archives, and verifies that retry selection is deterministic. It does not create
GitHub releases, push tags, or modify real documentation snapshots.

## Repository rollout

Required repository settings: Pages uses **GitHub Actions** as its source;
`main` can deploy to the `github-pages` environment; and **Documentation** is a
required GitHub Actions status check for `main`. These are configured in GitHub,
not by the workflow YAML. Preserve other existing protection settings when
recreating or modifying them.
The check is defined in CI / Release and runs on every PR, including code-only PRs.

Merge the workflows before expecting these checks or the manual deployment button
to exist on `main`. After the first run, verify the landing page, version menu,
Mermaid diagrams, and a nested page at the real GitHub Pages URL. A successful local
build cannot confirm repository permissions or a live deployment.
