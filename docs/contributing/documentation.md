# Documentation maintenance and releases

README is the project introduction and quickstart; HACKING is the contributor
entry point. Detailed content lives in `docs/` and is rendered directly by
Docusaurus. Keep examples and links useful when reading Markdown on GitHub too.

## Local checks

Use Node.js 22 or later (CI uses 22) and a full JDK. Local Python commands use
`./scripts/uv`, which selects the managed interpreter and scripts project; that
project requires Python 3.15 or later and pins 3.15. See
`scripts/AGENTS.md` in the repository for wrapper behavior.
The npm scripts and CI currently invoke `python3` directly; they do not use the
managed wrapper or enforce the scripts project's Python requirement. For local
npm commands, `./scripts/uv run npm ...` supplies the managed Python environment.
From the repository root:

```sh
./mvnw -B -ntp package -DskipTests
./scripts/uv run npm --prefix website ci
./scripts/uv run python scripts/check_docs.py
./scripts/uv run python scripts/docs_release.py check
./scripts/uv run python -m unittest discover -s scripts -p 'test_*.py'
./scripts/uv run npm --prefix website run build
./scripts/uv run python scripts/check_site.py
```

Preview with `./scripts/uv run npm --prefix website start`, or serve the production build with
`./scripts/uv run npm --prefix website run serve`. Open the printed URL under `/cs1302-tracer/`.
The build wrapper owns `website/build`; custom output directories, site roots,
and configuration overrides are rejected before generation. `start` accepts
`--host`, `--port` (or `-p`), and `--no-open`; both `start` and `build` accept
`--no-minify`.

Both `start` and `build` generate Javadoc first; `./scripts/uv run npm --prefix website run api`
generates it separately. Fetch release tags with `git fetch --tags` before building.
For a direct Maven invocation, run `./mvnw -B -ntp validate javadoc:javadoc`.
The wrapper includes `validate` so the build-helper execution sets `currentYear`
before Javadoc resolves the copyright footer.
The **API Reference** navigation link follows the selected documentation version.
Javadoc keeps its own navigation, search, and styling under `api/next/` for development
and `api/<version>/` for releases, beneath the site's `/cs1302-tracer/` base path.

Release API references are rebuilt from their exact Git tags on every deployment,
including all previously published documentation versions. Missing tags, mismatched
Maven versions, and Javadoc errors fail the build. Deployment selects the same
published releases for guides and API references. Local builds preview all prepared
snapshots; an untagged snapshot matching the current Maven version uses the working
source until release. Generated HTML lives in ignored `website/generated-api/` and
is copied into the site build, rather than committed.

Build mode is explicit: `start`, `build`, and `api` default to `--mode preview`.
A leftover `published-versions.json` never changes their behavior. To reproduce a
published build, save the GitHub release records as a JSON array and run:

```sh
./scripts/uv run npm --prefix website run build -- --mode published --releases /path/to/releases.json
```

The Python documentation release module validates selection, tag provenance, and
routes once. It generates ignored `website/release-manifest.json` for Javadoc,
Docusaurus, and navigation. The completed build includes its own copy of the
manifest so validation and local serving use the decisions belonging to that build.
The legacy `docs_release.py published` command still exports a selected-version list
for inspection; that file no longer selects the build mode.

`check_docs.py` compares CLI help to the executable, runs the marked quickstart
blocks, checks Markdown file destinations, compares both JSON examples after
normalizing heap identities, and runs the documented batch request. Refresh help
using `./scripts/uv run python scripts/check_docs.py --update-cli`; review the resulting diff.
When behavior changes, update JSON examples intentionally and review the contract.
The site build and `check_site.py` check rendered internal links, fragments, and
project-base-path handling. Prose accuracy still requires review.

The scheduled External documentation links workflow checks remote links and
uploads a report. It is not a required PR check because remote availability is
outside this repository's control. A newly configured site can return 404 until
its first deployment.

## Prepare a stable release

Versioned documentation begins with the first release using this workflow.
The tracked snapshots start at 3.2.1; older software releases have no documentation
snapshot. The preparation command rejects an already-tagged version.

1. Fetch release tags with `git fetch --tags`. Set the intended **new**, stable `x.y.z` version in `pom.xml` and finish the docs.
2. Build the JAR, regenerate CLI help if necessary, and run documentation checks.
3. Run `./scripts/uv run npm --prefix website run release:prepare`. This snapshots `docs/` and the
   sidebar using the Maven version. Review and commit `website/versions.json`,
   `website/versioned_docs/`, and `website/versioned_sidebars/` with the release changes.
4. Merge the release preparation into `main` before tagging. Never remove earlier
   snapshots. If preparation changes, edit the pending snapshot to match `docs/`
   and run `./scripts/uv run python scripts/docs_release.py check --tag vX.Y.Z` again.
5. Push a tag `vX.Y.Z` (or `X.Y.Z`) at that merged commit. CI requires exact agreement
   among the tag, Maven version, current docs, snapshot, and sidebar before the JAR
   release job can run. This pipeline handles stable releases only.

Local builds show all prepared snapshots so reviewers can inspect them. Deployment obtains
published, non-prerelease GitHub releases and filters snapshots against that list.
A prepared but unpublished snapshot therefore never becomes the public default.
The highest published semantic version becomes the default, even if an older
release is published later. Each release keeps its version URL; development uses
`/next/`.

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

Run `./scripts/uv run python scripts/rehearse_docs_release.py` after installing site dependencies.
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

The workflow files define these checks and deployment triggers. After deployment,
verify the landing page, version menu, Mermaid diagrams, and a nested page at the
real GitHub Pages URL. Repository protection, Pages permissions, and live deployment
state must be verified in GitHub; their presence cannot be inferred from tracked
configuration or a successful local build.
