# Documentation maintenance and releases

README is the project introduction and quickstart; HACKING is the contributor
entry point. Detailed content lives in `docs/` and is rendered directly by
**Docusaurus**. Keep examples and links useful when reading Markdown on GitHub too.

Bold the first prose or table mention of a named program or tool on each page,
including an adjacent version requirement. Leave later mentions plain and preserve
headings, inline commands, and code blocks. This convention does not apply to
operating systems, language names, libraries, or file formats. Apply it to current
guides and entry points; preserve historical reviews and published snapshots.

The Scripts quality CI job separately enforces Python lint, formatting, types,
and 100% production line/branch coverage, including child processes. See the
[development guide](development.md#python-script-quality) for the complete local
gate. The documentation job still checks real tracer output and builds the site;
external-tool stand-ins in the Python suite do not replace that integration check.

## Local checks

Use a full **JDK** and fetch release tags with `git fetch --tags`. From the repository
root, build and verify the complete site with **Maven**:

```sh
./mvnw -B -ntp -Pdocsite verify
```

The opt-in profile uses **frontend-maven-plugin 2.0.2** to install **Node.js 24.22.0 LTS**
and its bundled **npm 11.19.1** under `target/frontend/`. No global Node/npm is needed.
It runs `npm ci` against `website/package-lock.json`, packages the tracer, then
checks executable documentation and release metadata, generates **Javadoc** and the
site, and checks rendered links. Any failed step fails Maven. Ordinary Maven builds
leave the frontend toolchain alone. `clean` removes the local toolchain under
`target/`; downloaded distributions remain in Maven's cache.

CI documentation and deployment jobs use this profile with `-DskipTests`; the Java
matrix owns their Java tests. Locally, omit that flag for the complete build.
The existing Python orchestration remains responsible for release selection and
Javadoc generation. Nested Maven Javadoc invocations explicitly deactivate
`docsite` to prevent recursive frontend builds. The plugin puts its local Node/npm
on the child process PATH; `build:maven` enters the managed Python environment
through `scripts/uv`. Python 3.15 and locked tooling are provisioned by that wrapper.
The complete site workflow uses the repository's Unix shell tooling on Linux/macOS;
this profile does not establish native Windows docsite support.

For live editing, bootstrap the frontend without packaging, then use its tools:

```sh
./mvnw -B -ntp -Pdocsite generate-resources
export PATH="$PWD/target/frontend/node:$PATH"
./scripts/uv run --locked npm --prefix website start
```

An existing compatible global Node installation (22 or later) still works with the
manual npm workflow. Run npm through `./scripts/uv run --locked` so its Python
children use the managed interpreter. To serve the completed build without Node,
run `./scripts/uv run --locked python scripts/serve_docs.py --port 3100`.
Open the printed URL under `/cs1302-tracer/`.
The build wrapper owns `website/build`; custom output directories, site roots,
and configuration overrides are rejected before generation. `start` accepts
`--host`, `--port` (or `-p`), and `--no-open`; both `start` and `build` accept
`--no-minify`.

Both `start` and `build` generate Javadoc first; `./scripts/uv run npm --prefix website run api`
generates it separately. Fetch release tags with `git fetch --tags` before building.
For a direct Maven invocation, run `./mvnw -B -ntp validate javadoc:javadoc`.
The wrapper includes `validate` so the build-helper execution sets `currentYear`
before Javadoc resolves the copyright footer.
The published API reference intentionally excludes `cs1302.tracer.internal` and
its subpackages. When internal Javadoc changes, also generate a local reference
that includes private declarations, then review the affected generated pages:

```sh
./mvnw -B -ntp package -DskipTests
javadoc -quiet -private --release 21 -Xdoclint:all,-missing \
  -classpath target/code-tracer.jar \
  -sourcepath src/main/java -subpackages cs1302.tracer \
  -overview src/main/javadoc/overview.html \
  -d target/internal-apidocs
```

This local reference is for contributor review; internal declarations remain
outside the supported Java API. Both generation commands check Javadoc syntax
and links, but claims about defaults, ownership, and behavior still need to be
checked against the implementation and tests.

The **API Reference** navigation link follows the selected documentation version.
Javadoc keeps its own navigation, search, and styling under `api/next/` for development
and `api/<version>/` for releases, beneath the site's `/cs1302-tracer/` base path.

Release API references are rebuilt from their exact **Git** tags on every deployment,
including all previously published documentation versions. Missing tags, mismatched
Maven versions, and Javadoc errors fail the build. Deployment selects the same
published releases for guides and API references. Local builds preview all prepared
snapshots; an untagged snapshot matching the current Maven version, or its target
version after removing a trailing `-SNAPSHOT`, uses working source until release.
Such a snapshot has an unreleased banner in preview mode. Published mode still
requires a release tag whose Maven version exactly matches the stable snapshot;
a `-SNAPSHOT` build cannot substitute for a published release.
After release preparation advances the POM to the next development version, CI
can preview the still-untagged release with
`-Ddocsite.pendingCommit=<full-release-commit-SHA>`. The corresponding Python option
is `--pending-commit`, defaulting to `DOCSITE_PENDING_COMMIT`. This preview-only
input must name a stable ancestor of the checked-out revision whose version is
the newest prepared snapshot. Its committed docs, snapshot, version list, and
sidebars must agree with the checkout. Javadoc then comes from that exact commit,
and the snapshot retains its unreleased banner. Published builds reject this
override and continue to require release tags. Historical snapshots never fall
back to an arbitrary working tree.
The documentation CI job bootstraps the pinned frontend toolchain with
`-Pdocsite generate-resources` and adds its Node directory to `GITHUB_PATH` before
running the release-context check. That check handles both `release/X.Y.Z` and
the next-development commit on `main` during the interval before the tag push.
It requires the untagged stable release to be that commit's direct parent; it
does not infer an arbitrary release source from the available history.
Generated HTML lives in ignored `website/generated-api/` and
is copied into the site build, rather than committed.

Build mode is explicit: the Maven property `docsite.mode` defaults to `preview`.
Set it to `published` with `docsite.releases` pointing to an absolute path to a JSON
array of GitHub release records. These properties reach the Python process as
`DOCSITE_MODE` and `DOCSITE_RELEASES`, avoiding shell interpolation of paths.
The Python actions `start`, `build`, `api`, and `verify` default to preview when
those environment variables are absent; explicit `--mode` and `--releases` options
override their corresponding environment defaults. `verify` runs the complete
checks around a build and accepts no forwarded Docusaurus flags.
A leftover `published-versions.json` never changes their behavior. To reproduce a
published build, save the GitHub release records as a JSON array and run:

```sh
./mvnw -B -ntp -Pdocsite -Ddocsite.mode=published -Ddocsite.releases=/path/to/releases.json verify
```

The Python documentation release module validates selection, tag provenance, and
routes once. It generates ignored `website/release-manifest.json` for Javadoc,
Docusaurus, and navigation. The completed build includes its own copy of the
manifest so validation and local serving use the decisions belonging to that build.
The legacy `docs_release.py published` command still exports a selected-version list
for inspection; that file no longer selects the build mode.

`check_docs.py` compares CLI help to the executable, runs the marked quickstart
blocks, checks Markdown file destinations, compares both JSON examples after
normalizing heap identities, and runs the documented batch request. It also extracts
`target/code-tracer-bin.zip` into a temporary directory with spaces, executes the
first-trace tutorial with its own source file, compiles and runs the complete Java
tutorial, and checks both jobs from the batch tutorial. Package the project first;
a JAR-only build is insufficient. These checks run the marked commands with real
Java tools. Python unit tests substitute those external tools to test checker
failures, and do not replace this integration run.

Keep tutorial marker names stable when editing examples. The first-trace check
asserts source identity, output, a local value, and its object-reference relationship
without pinning heap IDs. Java and batch checks inspect completion and failure
outcomes. The README quickstart remains a separate checkout-based example.

Refresh help
using `./scripts/uv run python scripts/check_docs.py --update-cli`; review the resulting diff.
The CLI reference keeps generated help and budget links above
`<!-- cli-reference:manual -->`. Maintain behavioral notes below that marker;
help regeneration preserves those notes, and normal checks still require an exact
match for the generated prefix. Link checks cover the entire document. Review
authored notes against executable behavior whenever arguments change.
When behavior changes, update JSON examples intentionally and review the contract.
The site build and `check_site.py` check rendered internal links, fragments, and
project-base-path handling. Prose accuracy still requires review.

The scheduled External documentation links workflow checks remote links and
uploads a report. It is not a required PR check because remote availability is
outside this repository's control. A newly configured site can return 404 until
its first deployment.

## Prepare a stable release

**Maven Release Plugin 3.3.1** owns the stable-version commit, signed annotated
`vX.Y.Z` tag, and next-development commit. **GitHub Actions** remains the publisher.
The pinned plugin uses external Git, `signTag=true`, and `pushChanges=false`.
The supported interface is `scripts/release.py`; do not replace its verified
sequence with ad hoc version edits or pushes. Do not run `release:perform`:
its default Maven deployment would introduce a separate publication path.
See Apache's [preparation parameters](https://maven.apache.org/maven-release/maven-release-plugin/prepare-mojo.html)
and [performance parameters](https://maven.apache.org/maven-release/maven-release-plugin/perform-mojo.html).

### Select versions and prepare documentation

Choose both versions explicitly. Development uses `MAJOR.MINOR.PATCH-SNAPSHOT`;
stable releases use `MAJOR.MINOR.PATCH`. An incompatible supported Java API, CLI,
JSON/batch protocol, or documented runtime requirement requires a major increment.
Compatible functionality requires at least a minor increment; compatible fixes
and maintenance require at least a patch increment. The current snapshot number
does not override this assessment.

The release command requires `--change-kind major`, `minor`, or `patch`, and checks
the selected versions against the highest published stable GitHub release. This
classification is the maintainer's declaration, not an automated compatibility
analysis: inspect the actual changes and changelog before choosing it. The next
development version must be a higher snapshot. No release version is selected by
these instructions; replace the placeholders below with reviewed values.

Reconcile `CHANGELOG.md` against the real release range. Keep the pending summary
in `Unreleased` until publication supplies its actual date and final commit IDs.
Finish current docs, build the executable, refresh CLI help when needed, and check
the documented behavior. Set the development POM to the selected release's
`X.Y.Z-SNAPSHOT` as part of the ordinary reviewed changes, then prepare the stable
documentation without changing that POM:

```sh
git fetch --tags
./mvnw -B -ntp -Pdocsite generate-resources
export PATH="$PWD/target/frontend/node:$PATH"
./scripts/uv run --locked python scripts/docs_release.py prepare --version X.Y.Z
./scripts/uv run --locked python scripts/docs_release.py check --pending X.Y.Z
```

The explicit target must match the stable or snapshot Maven version. Preparation
rejects an existing tag, an existing snapshot, or a non-increasing version. Review
and commit `website/versions.json`, `website/versioned_docs/`, and
`website/versioned_sidebars/` with the release documentation, then integrate those
changes into `main` through the normal reviewed process. If current docs or the
sidebar change afterward, edit the pending snapshot to match and rerun
`check --pending X.Y.Z`; do not regenerate historical snapshots. The strict
`check --tag vX.Y.Z` still requires a stable POM and is used during real release
preparation. Tracked documentation snapshots begin at 3.2.1; older software
releases have no corresponding snapshot.

### Prepare the signed release locally

Use a full JDK on Linux or macOS, the repository wrappers, and an authenticated
**GitHub CLI** with access to read releases and workflow runs and push the
authorized refs. Start with a clean checkout, including untracked files, at the
current `origin/main`. The command fetches `main` and tags and rejects a different
source revision. The destination must not exist: it creates a separate clone and
its `release/X.Y.Z` branch, preserving the source checkout.
The effective fetch and push URLs for `origin` must identify the same single
destination. GitHub evidence queries select that repository explicitly; ambient
`GH_REPO` or `GH_HOST` settings cannot redirect the checks.

Configure `user.name`, `user.email`, `user.signingKey`, and `gpg.format=ssh` in Git,
with a usable signing key and an existing `gpg.ssh.allowedSignersFile` containing
the trusted release signer. Verify this trust configuration independently; the
tool does not invent trusted signers or copy private keys into the repository.
It carries the signing settings into the isolated clone, requires commit/tag
signing, and preserves the existing staged-index pre-commit hook. Missing signing
or failed signature verification stops preparation.

```sh
./scripts/uv run --locked python scripts/release.py prepare \
  --directory /absolute/path/to/new-release-checkout \
  --release-version X.Y.Z \
  --development-version A.B.C-SNAPSHOT \
  --change-kind major
```

Here `major` is an example classification; choose the reviewed `major`, `minor`,
or `patch` value. `--source PATH` selects a different clean source checkout;
otherwise the script's repository is used.

Preparation first rehearses POM/SCM transformations with `release:prepare
-DdryRun=true`. This rehearsal uses `-Drelease.preparationGoals=validate` and an
empty `-Drelease.preparationProfiles=` because Maven executes dry-run goals
against the original snapshot POM. It does not constitute the full release gate.
The tool removes that rehearsal state with `release:clean`, then runs the real
preparation with the configured `verify` goals and mandatory
`pre-commit-coverage,docsite,release-validation` profiles against stable-version
sources. Do not override those gates during a real preparation.

Before creating the release commit and tag, the real gate runs full Java tests
with 100% production line and branch coverage; Python lint, format, type, and 100%
coverage checks including subprocesses; trace fixtures; local launcher regression
and packaged-launcher smoke checks; exact release-snapshot agreement; executable
documentation; public/internal Javadoc; and the docsite build and rendered links.
Guest execution and documentation checks run serially. Previously generated Java
coverage is preserved as audit evidence instead of being merged into a fresh
measurement. CI supplies the additional configured OS/JDK and native Windows
coverage; local success does not substitute for CI.

Successful preparation leaves two signed commits and a signed annotated tag
locally. The release commit is the source `main` commit's direct child; the
next-development commit is the release commit's direct child. Both transitions
may change only the POM's project version and SCM tag; other source or POM changes
require normal review before preparation. The report records
the selected versions, source revision, remote, branch, both commit IDs, and tag
object. It lives in `.git/code-tracer-release.json`; Maven logs live in
`.git/release-logs/`, outside `target/`. The command verifies signatures, versions,
tag target, graph, and a clean checkout before reporting `prepared`.

```sh
./scripts/uv run --locked python scripts/release.py status \
  --directory /absolute/path/to/new-release-checkout
```

`status` prints the saved report; it does not rerun validation or refresh remote
evidence. Preparation pushes no refs and does not create a GitHub release.
An instruction to prepare authorizes only that phase; an explicit instruction to
prepare and publish authorizes both phases without an additional confirmation.

Local builds show all prepared snapshots so reviewers can inspect them. Deployment obtains
published, non-prerelease GitHub releases and filters snapshots against that list.
A prepared but unpublished snapshot therefore never becomes the public default.
The highest published semantic version becomes the default, even if an older
release is published later. Each release keeps its version URL; development uses
`/next/`.

## Publishing and retrying

After review and publication authorization, run:

```sh
./scripts/uv run --locked python scripts/release.py publish \
  --directory /absolute/path/to/new-release-checkout
```

Each invocation checks the local signed release graph and remote refs, then
advances only as far as verified evidence permits:

1. Push the stable release commit to `release/X.Y.Z`, without the tag. Require a
   successful push-triggered CI / Release run on that exact commit, including
   every required documentation, Python, Java OS/JDK, and Windows launcher job.
2. Fast-forward that same release branch to the next-development commit. Require
   another complete successful push run on that exact commit. CI pins the pending
   documentation preview to the stable parent while its tag remains local.
3. Fast-forward `main` to the tested next-development commit, preserving both
   signed commit IDs, and verify the resulting remote ref. Existing required
   checks and branch protections remain in force.
4. Push only the verified signed release tag. Every push disables implicit
   follow-tags behavior, so earlier branch pushes cannot publish it accidentally.
5. Require the exact tag's successful push-triggered CI run, including its
   publisher job, then verify release assets and deployed documentation provenance.

The command returns **0** after publication is verified, **3** while a required CI
run, release artifact, or documentation deployment is still pending, and **1** on
invalid state or a failed operation. It does not wait indefinitely. Rerun the same
`publish` command after pending work completes; it resumes from verified remote
state without recreating commits or tags. Failed, cancelled, skipped, missing, or
duplicated required CI jobs do not count as successful release evidence.
Evidence must cover every required job in the latest run attempt. If a partial
rerun exposes only the retried jobs, choose **Re-run all jobs** for that workflow
run, wait for all required jobs to pass, then retry `publish`. The tool does not
combine incomplete evidence from separate attempts.

Do not squash, rebase, amend, or force-push the generated release history. The tool
stops if `main` advances without containing the prepared next-development commit,
or if remote branch/tag identities change. Once integration has happened,
publication retries may accept later `main` descendants that still contain that
exact prepared history; they never rewind `main`. Inspect unexpected state and
reprepare from an approved base when necessary.
Never move or reuse a published release tag. The workflow verifies that a release
tag's commit is reachable from `main`, as well as its stable POM and snapshot
agreement, before publication.

Publication verification downloads the standalone and library JARs and the three
binary archives. Both JARs must carry the selected version and clean tagged commit;
every archive must contain the same executable JAR. The live published documentation
manifest must identify that release and commit without an unreleased banner.
Only then does the report say `published`. Inspect the deployed landing page,
version menu, diagrams, and a nested page as the human review of rendered content.
Finalize the changelog using actual publication metadata and links for every commit,
including the generated release commit, in a follow-up change.

The Deploy documentation workflow runs after successful push runs of CI / Release
on `main` and release tags, excluding preparation branches, or through **Run
workflow** on `main`. It checks out the
latest `main`, verifies docs again, builds the complete site, uploads a Pages
artifact, and deploys using the `github-pages` environment. The workflow is
serialized with cancellation disabled. Every run rebuilds the latest state so a
retry of an older release cannot replace newer docs with an old checkout.

GitHub releases created by the CI token do not need to trigger another release
event: deployment uses `workflow_run` after CI / Release completes. The software
release publishes the standalone executable JAR, ordinary library JAR, and
tar.gz, tar.xz, and ZIP archives containing the **Bash**, **PowerShell**, and batch launchers.
It needs documentation validation, but does not depend on Pages deployment.
For an outage, rerun Deploy documentation or dispatch it on `main`; do not recreate
the tag or software release. Build artifacts are also downloadable from PR checks
for local review; PRs do not deploy publicly.

Release snapshots are retained in Git. Correct an older release's documentation by
editing its snapshot in a reviewed PR; do not describe unreleased behavior there.
For a broken site deployment, revert the responsible docs/configuration change on
`main` and dispatch deployment again. Deleting a published release removes it from
the next public version selection, but does not delete its source snapshot.

### Recover according to the recorded and remote state

Preserve `.git/code-tracer-release.json`, `.git/release-logs/`, Maven
`release.properties`, backup POMs, and any needed validation evidence before cleanup.
Inspect Git history, local tags, remote branch/tag refs, and the saved phase before
choosing recovery. A saved report alone is not proof that no remote action occurred.

- If the initial dry run fails, inspect its logs and simulated POMs. No real release
  history should have been created. Correct the cause and use a new isolated
  preparation directory after checking the failed attempt's state.
- If a real preparation was interrupted while the report says `preparing`, use
  `scripts/release.py resume --directory PATH` through `scripts/uv run --locked
  python`. Resume refuses an already-pushed release branch or tag, lets Maven use
  its saved preparation state, then verifies the completed signed history.
- If local preparation must be abandoned, first establish that none of its release
  refs reached the remote. Inspect the pinned plugin's `release:rollback` behavior
  and Maven state before running it in the isolated checkout with pushes disabled.
  Review the resulting commits, tags, and backup files; rollback is not a generic
  reset operation. Preserve the original source checkout and audit evidence.
- If branch CI fails, fix the cause through the normal development process. Do not
  edit the prepared checkout and retain its old tag as if it still described the
  new sources. Publication stops until the actual required checks pass.
- If `main` was integrated or the tag was pushed, reconcile the exact remote state
  before any recovery. Publication retries can continue pending work; do not apply
  a blind local rollback to already-integrated history.
- If the software release exists but Pages deployment fails, rerun or dispatch
  Deploy documentation and rerun `publish` to verify it. Do not recreate the release.

## Release rehearsal

The release command's dry run rehearses Maven's POM/SCM preparation only. Before
changing release orchestration, also exercise actual preparation, signatures,
version transitions, local tagging, failure, resume, and rollback in a disposable
checkout with an isolated remote. Never use the real release tag or publication
destination for that rehearsal. Script tests use real temporary Git repositories
and external-tool stand-ins; they do not establish that a real release build or
GitHub publication succeeded.

The separate documentation rehearsal uses actual site builds. After frontend
bootstrap, run it with the same pinned toolchain:

```sh
export PATH="$PWD/target/frontend/node:$PATH"
./scripts/uv run --locked python scripts/rehearse_docs_release.py
```

It creates two artificial versions in a disposable tree, builds the site before
and after publishing the second version, checks stable routing and retained
archives, and verifies that retry selection is deterministic. It does not create
GitHub releases, push tags, or modify real documentation snapshots.

## Repository rollout

Required repository settings: Pages uses GitHub Actions as its source;
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
