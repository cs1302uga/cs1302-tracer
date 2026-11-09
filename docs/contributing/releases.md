# Prepare and publish releases

Use this runbook for software version selection, signed preparation, publication,
and recovery. Preparation and publication are separate operations. For guide-only
historical guide corrections, use [retained guides](guide-archives.md); for site build
settings and local preview, use [documentation builds](documentation-builds.md).

## Prepare a stable release

**Maven Release Plugin 3.3.1** owns the stable-version commit, signed annotated
`vX.Y.Z` tag, and next-development commit. **GitHub Actions** remains the publisher.
The pinned plugin uses external **Git**, `signTag=true`, and `pushChanges=false`.
The supported interface is `scripts/release.py`; do not replace its verified
sequence with ad hoc version edits or pushes. Do not run `release:perform`:
its default **Maven** deployment would introduce a separate publication path.
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
the documented behavior. Refresh and review the
[license and dependency references](documentation-authoring.md#maintain-license-and-dependency-pages)
before preparing the snapshot. Their generated project root label is independent
of the project version, so the release plugin's version-only transitions preserve
agreement while the dependency versions remain exact. Set the development POM to
the selected release's `X.Y.Z-SNAPSHOT` as part of the ordinary reviewed changes,
then prepare the stable documentation without changing that POM:

```sh
git fetch --tags
./scripts/uv run --locked python scripts/docs_release.py prepare --version X.Y.Z
./scripts/uv run --locked python scripts/docs_release.py check --pending X.Y.Z
```

The explicit target must match the stable or snapshot Maven version. Preparation
rejects an existing tag, an existing snapshot, or a non-increasing version. Review
and commit `src/site/versions.json`, `docs/releases/`, and
`src/site/releases/` with the release documentation, then integrate those
changes into `main` through the normal reviewed process. If current docs or the
sidebar change afterward, edit the pending snapshot to match and rerun
`check --pending X.Y.Z`; do not regenerate historical snapshots. The strict
`check --tag vX.Y.Z` still requires a stable POM and is used during real release
preparation. Tracked documentation snapshots begin at 3.2.1; older software
releases have no corresponding snapshot.

### Prepare the signed release locally

Use a full **JDK** on Linux or macOS, the repository wrappers, and an authenticated
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
documentation; fresh license/dependency-reference validation; public/internal
**Javadoc**; and the docsite build and rendered links.
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

The signed version-transition branch is validated through push-triggered CI.
Do not open a pull request for those transitions: the pull-request documentation
job does not supply the pending stable-parent context needed before the release
tag is published. Its missing-tag failure can block the same commit even when
push CI passes. Review ordinary source and snapshot changes before preparation;
monitor the release branch's push runs during publication.

After review and publication authorization, run:

```sh
./scripts/uv run --locked python scripts/release.py publish \
  --directory /absolute/path/to/new-release-checkout
```

Each invocation checks the local signed release graph and remote refs, then
advances only as far as verified evidence permits:

1. Push the stable release commit to `release/X.Y.Z`, without the tag. Require a
   successful push-triggered CI / Release run on that exact commit, including
   every required documentation, Python, Java OS/JDK, and Windows launcher job,
   plus the `Regression gates` aggregate. The aggregate requires successful job
   groups; exact-revision evidence still checks each individual job too.
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
every archive must contain the same executable JAR. It also verifies the release
Javadoc JAR, checksum, metadata, and attestation against that exact tag and the
trusted publisher workflow. The live published documentation
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
It also publishes the standard Javadoc JAR and its checksum and attestation bundle.
The publisher names these files explicitly; a wildcard does not decide the release
asset set. Before upload it validates the clean stable identity, creates the
attestation for new Javadoc assets with pinned `actions/attest` version 4.2.2,
or reuses a complete verified existing bundle. It verifies the bundle's
repository, workflow, commits, tag ref, and hosted-runner identity.
It needs documentation validation, but does not depend on Pages deployment.
For an outage, rerun Deploy documentation or dispatch it on `main`; do not recreate
the tag or software release. Build artifacts are also downloadable from PR checks
for local review; PRs do not deploy publicly.

Release snapshots are retained in Git. Correct an older release's documentation by
editing its snapshot in a reviewed PR; do not describe unreleased behavior there.
For an archived guide, also follow the
[archive correction procedure](guide-archives.md#roll-out-archived-guides) to publish
and review its source/navigation provenance.
Python commands must match the checkout they document: current guides use
`./scripts/uv run python3` (or the equivalent wrapped `python`), while the 3.2.x
checkouts predate that wrapper and retain their direct `python3` instructions
with a historical-tooling notice. Preserve historical audit evidence when updating
command guidance.
For a broken site deployment, revert the responsible docs/configuration change on
`main` and dispatch deployment again. Removing an unpinned software release changes
its eligibility for public version selection while retaining its source snapshot.
Once a version is pinned in release provenance, missing or reclassified
software release metadata and changed tags fail deployment instead of silently
retiring its published documentation.

### Recover according to the recorded and remote state

Preserve `.git/code-tracer-release.json`, `.git/release-logs/`, Maven
`release.properties`, backup POMs, and any needed validation evidence before cleanup.
Inspect Git history, local tags, remote branch/tag refs, and the saved phase before
choosing recovery. A saved report alone is not proof that no remote action occurred.

- If prepared history predates `Regression gates`, its original workflow cannot
  produce that job through a rerun. The current publisher requires the aggregate.
  Preserve the prepared revisions and their evidence; recover with the matching
  historical tooling and validation contract, or prepare a new unpublished
  candidate from a base containing the current workflow. Check the effective
  branch rules before choosing either path. Published tags and assets retain
  their original identities and validation scope.
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
- If a release has only part of the Javadoc JAR/checksum/attestation set, stop and
  inspect its assets and the original publisher logs. Preserve the evidence and
  establish which exact attested bytes belong to the tag before an explicitly
  reviewed manual repair. Repeated workflow runs do not overwrite or silently
  rebuild that partial set. Recover the original matching companions or publish a
  new version; do not move the tag to work around the failure.
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

The separate documentation rehearsal uses actual Maven Site builds:

```sh
./scripts/uv run --locked python scripts/rehearse_docs_release.py
```

It uses the explicit Javadoc source override because its artificial releases have
no published artifacts. It creates two versions in a disposable tree, builds before and after
selecting the second published release, checks stable/latest and development
channels, retains older guides, repeats selection, and rejects missing tags while
preserving previous complete API output. It makes no GitHub release or ref changes.

## Maven Central publication

Releases adopting `release.central.schema=1` publish Maven coordinates
`io.github.cs1302uga:cs1302-tracer` as well as the GitHub distributions. Earlier
releases retain their original GitHub-only evidence contract. Changing the old
`cs1302uga` group ID is a breaking Maven consumer migration. The maintainer
explicitly approved release 4.3.1 as an exception to the normal major-release
policy; this exception does not change the policy for subsequent releases.
That historical release used `--change-kind patch` for the approved increment,
retained its breaking migration notice, and transitioned to `4.3.2-SNAPSHOT`. Java package names,
CLI arguments, and JSON formats are unchanged. Snapshots are not published by this
workflow. The existing GitHub Packages distribution settings are independent of
GitHub Releases and are not used by the Central bundle publisher.

New releases use `release.central.schema=2`, which additionally requires
`io.github.cs1302uga:cs1302-tracer-maven-plugin` at exactly the tracer version.
The launcher is compatible new functionality and requires at least a minor
increment when selecting the next software release. The root POM controls both
versions; release transition commits still change only that POM. Schema-one
releases retain the four-artifact contract, and published bundles remain immutable.

Configure these repository or accessible organization Actions secrets before
publishing: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_TOKEN`,
`MAVEN_GPG_PRIVATE_KEY`, and `MAVEN_GPG_PASSPHRASE` for a protected signing key.
The Central token must have permission for `io.github.cs1302uga`. Publish the PGP
public key as required by Central. SSH release-history signatures and GitHub
Sigstore attestations remain separate from PGP artifact signatures. Never put
credentials or private keys in the POM, source tree, release assets, or notes.

The `release-central` profile uses Maven Source Plugin 3.3.1, Maven GPG Plugin
3.2.7, and Central Publishing Maven Plugin 0.12.0. With `release-javadoc`, it
builds the ordinary library JAR, sources JAR, real API Javadoc JAR, and POM, with
PGP signatures and MD5/SHA-1/SHA-256/SHA-512 checksums. Assemblies are still built
for GitHub but are not attached to the Central deployment. `skipUpload=true`
keeps Maven's `deploy` phase upload-free in this profile. For a signed local
bundle rehearsal, from a stable checkout with a configured signing key:

```sh
./mvnw -B -ntp -Prelease-javadoc,release-central deploy
./mvnw -B -ntp -f target/maven-plugin/pom.xml \
  -Dmaven.repo.local="$PWD/target/launcher-repository" -Dtracer.root="$PWD" \
  -Prelease-central -DskipTests deploy
```

Inspect `target/central-publishing/central-bundle.zip` and
`target/maven-plugin/target/central-publishing/central-bundle.zip`. These commands
do not publish them. Each contains its artifact's POM, binary, sources, and Javadoc
with signatures and checksums. `central_publish.py freeze` combines them for schema
two before validation and retention. It checks the launcher's POM against the tagged
template with the root version substituted, and checks the packaged plugin descriptor.
It verifies signatures for both families and rejects missing or extra entries.
After all public Central bytes match, verification resolves and runs the launcher
in a fresh temporary Maven repository from a directory without a POM, requiring
the released version in its output.
Retries restore the frozen combined bundle without needing either build directory. Portal validation can be rehearsed by manually uploading that bundle
with automatic publication disabled; do not publish an artificial rehearsal
version. Follow [Sonatype's Maven documentation](https://central.sonatype.org/publish/publish-portal-maven/)
and [Portal API documentation](https://central.sonatype.org/publish/publish-portal-api/).

After the existing tag gates pass, the workflow creates a GitHub draft and retains
`central-bundle.zip` before any Central upload. `central_publish.py freeze` verifies
its tagged POM, clean library provenance, exact inventory, checksums, PGP signatures,
and Javadoc metadata. On retry it downloads that bundle and restores the shared
library/Javadoc bytes. The existing Javadoc checksum and Sigstore checks still
apply. No later Maven lifecycle invocation may replace these frozen files.

The workflow invokes `central_publish.py` through `./scripts/uv run --locked python`.
Its required positional action is `freeze` or `publish`; both require
`--version X.Y.Z` (a stable version), `--commit SHA` (the full 40-character commit),
and `--repository OWNER/REPO` (the explicit GitHub repository). There are no
implicit version, commit, or repository defaults. `freeze` can create or update
a GitHub draft; `publish` can publish to Central. Both exit nonzero on invalid
inputs or failed verification. Keep these publisher internals inside the supported
release workflow; the Maven bundle rehearsal above does not invoke them.

`central_publish.py publish` saves `central-deployment.json` in the draft before
uploading, then records the returned deployment UUID. The record binds the bundle
SHA-256, version, and commit. Upload uses the Portal API with automatic publication;
status polling makes up to 60 checks with 30-second intervals, and public artifact
verification compares every bundle member with Central. Network requests add to
that interval budget; the release job has a 60-minute overall timeout. A timeout
leaves publication incomplete. Rerun the tag workflow to reuse the bundle and
deployment ID. The final GitHub release
is made public only after Central and GitHub artifact checks pass. The ordinary
release evidence command additionally downloads and compares the Central bundle
and shared GitHub JARs before reporting publication complete.

An interrupted upload with an empty deployment ID is deliberately ambiguous.
Inspect the named deployment in the Portal and recover its UUID into the existing
record while retaining the version, commit, and bundle hash. If no upload occurred,
confirm that externally before removing only the intent record and retrying.
Failed or manually managed deployments require inspection in the Portal; the
publisher never silently drops or replaces them. Preserve failed deployment
information for diagnosis. Never replace a retained bundle or published version
to bypass a mismatch. If Central succeeded and GitHub failed, retry completes
GitHub publication using the retained bytes. Published historical tags are unchanged.

After the first actual publication, resolve the released dependency in a fresh
Maven local repository without a custom repository declaration, compile and run
the Java integration example, and confirm sources and Javadoc resolution. Local
bundle checks and synthetic API tests do not establish live Central availability.
