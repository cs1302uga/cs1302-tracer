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

Release API references are resolved from their exact **Git** tags on every build,
including all previously published documentation versions. Published builds prefer
verified release Javadoc JARs; source builds can reuse validated historical output
from the generated-output cache. Missing tags, mismatched Maven versions, invalid
release artifacts, and generation errors fail the build. Deployment selects the same
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
`-Pdocsite initialize` and adds its Node directory to `GITHUB_PATH` before
running the release-context check. The subsequent `verify` invocation runs
`npm ci` once; the early bootstrap only needs Node to inspect the sidebar.
The local editing recipe still uses `generate-resources` to install the site's
dependencies before starting the development server. The context check handles
both `release/X.Y.Z` and the next-development commit on `main` during the interval
before the tag push.
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

For automatic Javadoc artifact selection, retain the full release API records,
including each asset's name, size, download URL, and SHA-256 digest. A list of
version names alone is insufficient to verify a published Javadoc bundle.

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

## Archived guide applications

The Maven property `docsite.guides` defaults to `all` and maps to `DOCSITE_GUIDES`;
`docs_site.py --guides all|active` overrides that environment default. `all` compiles
every selected guide snapshot. `active` compiles development, the two newest selected
stable releases, and at most one pending release. Older guides retain their existing
`/VERSION/` URLs as isolated applications with scripts, styles, and resources beneath
the same prefix. API references keep their separate `/api/VERSION/` routes and
artifact/cache rules. The version selector and all-versions listing include archived
guides; navigation crosses applications with normal page loads, preserving a matching
page when it exists in the destination version.

Preview active mode renders historical applications locally from the snapshots and
can reuse a validated generated-output cache. Published active mode requires a
reviewed `website/guide-archives.json` entry for every retired guide. Missing entries,
changed snapshots, unavailable files, checksum mismatches, and failed attestations
stop the build. Published active mode has no automatic source-rendering fallback.
Published deployment also guards versions already pinned in the archive catalog:
deleting or reclassifying their software release, or moving its tag, fails early
instead of silently removing historical documentation. The newest two versions
that are not yet pinned retain the existing published-stable eligibility rule.

Each version's publication consists of `cs1302-tracer-VERSION-guides.zip`, its
`.sha256` checksum, and its `.sigstore.json` attestation bundle. Inside the ZIP,
`guide-manifest.json` records the software release commit, snapshot digest,
documentation/builder commit, clean status, version mount, routes, and complete file
digests. Verification checks safe bounded ZIP entries, inventory, and checksums.
The attestation must identify this repository's `docs-archive.yml` workflow, exact
builder commit, `refs/heads/main`, and a GitHub-hosted runner. The catalog pins all
three remote files by canonical URL, size, and SHA-256. Published builds download
and verify them in private temporary storage before extraction.

The **Publish documentation archives** workflow is dispatched manually on `main`.
`expected_commit` must be the full current main SHA; `versions` is a nonempty list
of unique stable versions separated by commas without spaces, such as `3.2.1,3.2.2`.
Each version needs its software tag and retained snapshot. The clean builder must
match `HEAD`, `origin/main`, and the dispatch SHA. Inputs select data, never commands
or an arbitrary builder checkout. The pinned frontend toolchain renders guides;
this workflow does not run guest examples or generate Javadoc.

The publisher creates a lightweight `docs-FULL_BUILDER_SHA` tag at that exact commit
and a same-named prerelease with `make_latest: false`. This documentation namespace
uses main-branch attestations; software releases retain signed `vX.Y.Z` tags.
`docs-*` tags are excluded from CI / Release and automatic Pages deployment.
No new signing credential or repository-wide immutable-release setting is required.

New uploads stay in a draft until their actual remote files pass verification.
Existing complete sets are verified and reused byte for byte. Completed retries
skip uploads and release metadata edits; complete drafts may proceed to publication.
Partial, unexpected, or changed sets fail before replacement. The selected version
set is fixed for a builder SHA. A different selection needs a newly reviewed builder
commit. The workflow does not overwrite files or move existing documentation tags.

This is a repository publication policy. [GitHub releases can be edited](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository).
Unless GitHub's optional immutable-release feature is enabled,
authorized users can edit assets and tags; other release metadata can be edited as
allowed by the platform. Catalog digests and attestations reject replaced bytes or
changed identities. For partial publication or deletion, preserve available evidence
and recover original matching companions through reviewed repair, or publish from a
new reviewed builder and review replacement catalog entries. Reusing a published
tag to conceal different bytes is outside this workflow.

### Roll out archived guides

Deployment explicitly remains in `all` mode and the initial catalog is empty.
Implementation alone does not establish published backfill or activate archived guides.

1. Land the workflow and builder on reviewed `main`, retaining `all` mode. Local
   layout/metadata checks cannot create a genuine GitHub workflow attestation.
2. Dispatch **Publish documentation archives** on that main SHA. For the initial
   backfill, include all seven retained stable guide versions, including the two
   that remain active, so their reviewed catalog entries also protect release
   retention. At minimum, every guide leaving the active window needs an archive.
   Wait for the full workflow, including verification of the published files.
3. Download its `guide-archive-catalog-FULL_BUILDER_SHA` workflow artifact. Review the
   rendered archives, source identities, release records, and verification evidence.
   Merge the selected entries into `website/guide-archives.json` through a normal
   reviewed change, preserving unrelated approved entries. Candidates are not committed
   automatically.
4. Validate a published build with `-Ddocsite.guides=active` and actual release records.
   Check direct historical routes, assets, API links, version switching, the all-versions
   listing, and the merged sitemap.
5. Change `DOCSITE_GUIDE_MODE` in `.github/workflows/docs-deploy.yml` to `active` through
   review. Before each later release leaves the two-release active window, publish its
   archive and review its catalog entry. Failure remains a failure; an explicit reviewed
   switch to `all` is the source-rendering recovery option.

Correct historical guides through a reviewed snapshot change, a new builder's archive
publication, and a reviewed replacement catalog entry. This preserves URLs while
recording the documentation correction separately from the original software commit.
Archived images, styles, and renderer output remain the verified bytes in their
published archive. Correcting shared assets or the renderer for an archived version
requires a new archive revision and a reviewed catalog pointer; later global asset
changes do not propagate automatically into existing archives.

After installing the pinned frontend dependencies, inspect a local candidate with:

```sh
./scripts/uv run --locked python scripts/guide_build.py archive \
  --version 3.2.1 --output target/guide-archives
```

Both options are required; output is a directory for the ZIP and checksum. Dirty
candidates can be inspected but cannot pass publication checks. To verify an archive
and its adjacent checksum and attestation, replace the two commit placeholders below
with the full 40-character software-release and documentation-builder SHAs:

```sh
./scripts/uv run --locked python scripts/guide_archive.py verify \
  target/guide-archives/cs1302-tracer-3.2.1-guides.zip --version 3.2.1 \
  --release-commit FULL_RELEASE_COMMIT --builder-commit FULL_BUILDER_COMMIT
```

`guide_archive.py check|verify PATH` requires `PATH`, `--version`, `--release-commit`,
and `--builder-commit`. `check` validates clean archive structure, metadata, file
digests, and agreement with the working snapshot; it does not authenticate workflow
provenance. `verify` additionally validates both adjacent companions and their
attestation. Its optional `--repository OWNER/REPO` defaults to
`cs1302uga/cs1302-tracer`. Run either action from a repository root containing the
matching retained snapshot and sidebar. Both reject candidates marked dirty and
source-digest mismatches. Invalid arguments exit with status 2; invalid artifacts
or failed external verification exit nonzero.

`guide_publish.py`
has `plan`, `preserve`, `verify-local`, `verify-remote`, and `catalog` phases. Each
requires `--versions`, `--builder-commit`, `--repository OWNER/REPO`, and `--directory`.
Optional `--github-output PATH` appends `preserved` and `published` flags. `catalog`
also requires `--catalog-output PATH` and a published prerelease; `verify-remote`
accepts a complete draft for pre-publication checks. Invalid state or failed external
operations exit nonzero. These helpers do not create tags, upload, or edit releases.

### Compatibility with existing software releases

The GitHub API audit on 2026-10-17 found that all seven software releases represented
by the current retained guide snapshots, from 3.2.1 through 4.1.0, had `immutable: false` and only
the executable and library JAR assets. Additive backfill is therefore technically
possible: release notes can link to trusted documentation publications, or uniquely
named documentation assets can be added while preserving original tags and runtime
bytes. Existing software releases do not need to be recreated.

The implemented migration uses separate `docs-*` publications so documentation
corrections can evolve independently of software tags and do not require editing old
software releases. Optional links in old release notes can be considered after the
trusted archives are published. Backfilling historical Javadoc is a separate provenance
decision: a present-day rebuild needs an explicit backfill attestation and must not
claim to be an artifact produced by the original software release workflow. No
automatic edits to historical software releases are part of this implementation.

## Published Javadoc artifacts

The opt-in `release-javadoc` Maven profile attaches
`target/cs1302-tracer-VERSION-javadoc.jar` with the standard `javadoc` classifier
at `package`. It contains the public API reference, including its index, search,
and supporting files. Ordinary `package` builds do not create this artifact.
The release publisher builds the profile from the clean stable tagged commit and
publishes three files together:

- `cs1302-tracer-VERSION-javadoc.jar`
- `cs1302-tracer-VERSION-javadoc.jar.sha256`
- `cs1302-tracer-VERSION-javadoc.jar.sigstore.json`

The checksum identifies the exact JAR bytes. The JAR's metadata identifies its
version, clean source commit, generator, JDK version/vendor, and Maven version.
The Sigstore bundle carries the SLSA build-provenance attestation created by the
pinned `actions/attest` release step. Verification uses **GitHub CLI** to require
the expected repository and publisher workflow, matching signer and source commit,
the `refs/tags/vVERSION` ref, and a GitHub-hosted runner. A checksum alone, or a
signed Git tag without verified artifact provenance, does not establish that
the release workflow produced the JAR.
The site consumer trusts `cs1302uga/cs1302-tracer` and its
`.github/workflows/build.yml` publisher.

For a new release, the publisher uploads the runtime files to a draft first,
then uploads the Javadoc bundle and publishes the complete release. Runtime
files retain their existing replacement behavior when a workflow is retried.

Publisher retries preserve a complete existing Javadoc JAR, checksum, and
attestation bundle after verifying them against the same release identity. They
reuse those exact bytes and skip creating another attestation. A partial set
stops the workflow for explicit repair; it is not replaced by a newly generated
set. The Javadoc upload disables overwriting those three existing files, and a
final check downloads and verifies the files actually published on GitHub.

The publisher uses `javadoc_publish.py preserve` for this pre-upload check:

```sh
./scripts/uv run --locked python scripts/javadoc_publish.py preserve \
  target/cs1302-tracer-X.Y.Z-javadoc.jar --version X.Y.Z --commit FULL_RELEASE_COMMIT_SHA \
  --repository cs1302uga/cs1302-tracer
```

The JAR path must use that version's standard filename. The stable `--version`,
full lowercase `--commit`, and `--repository OWNER/REPO` are required. When a
complete remote set exists, the command verifies it and replaces the local
candidates with those exact files. A successfully inspected release with no
Javadoc assets permits fresh publication; a failed GitHub request does not.
The optional `--github-output PATH` appends `preserved=true` or `preserved=false`
for workflow decisions. Replace `preserve` with `verify` to download and check the
actual remote set after upload; that action does not replace the local candidates
and fails if the remote set is absent. Successful checks return 0, rejected
evidence returns nonzero, and missing required arguments or unknown options
return 2. Neither action uploads or removes remote assets.

The publisher first runs the local structure and metadata check, which also writes
the adjacent checksum file. Replace the version and full commit placeholders with
the stable tag's verified values:

```sh
./scripts/uv run --locked python scripts/javadoc_artifact.py check \
  target/cs1302-tracer-X.Y.Z-javadoc.jar --version X.Y.Z --commit FULL_RELEASE_COMMIT_SHA
```

After the attestation bundle exists, use `verify` to check all three adjacent files
and the trusted publication identity:

```sh
./scripts/uv run --locked python scripts/javadoc_artifact.py verify \
  target/cs1302-tracer-X.Y.Z-javadoc.jar --version X.Y.Z --commit FULL_RELEASE_COMMIT_SHA \
  --repository cs1302uga/cs1302-tracer
```

Both actions require the JAR path, `--version` as a stable `X.Y.Z` value, and
`--commit` as the full 40-character lowercase Git SHA. `verify` accepts
`--repository OWNER/REPO`, defaulting to `cs1302uga/cs1302-tracer`; `check` does
not use repository trust. For downloaded artifacts, substitute the downloaded
JAR's path and keep its companions beside it. Successful checks return 0;
invalid contents or failed verification return nonzero. Unknown options or missing
required arguments return 2.

The main section of `META-INF/MANIFEST.MF` carries
`Code-Tracer-Javadoc-Schema`, `Code-Tracer-Version`, `Code-Tracer-Commit`,
`Code-Tracer-Dirty`, `Code-Tracer-Javadoc-Plugin`, `Code-Tracer-Build-Jdk`,
`Code-Tracer-Build-Jdk-Vendor`, and `Code-Tracer-Build-Maven`. `check` validates
local content; it does not substitute for `verify` or publish a release.

All documentation actions accept `--javadoc-source auto|source`. The default is
`auto`; an explicit option overrides `DOCSITE_JAVADOC_SOURCE`. Maven forwards
`-Ddocsite.javadocSource=auto|source` through that variable. Other values are
argument errors and exit with status 2.

| Selection | Historical API behavior |
| --- | --- |
| Preview mode | Use tagged source and the generated-output cache; do not fetch release Javadoc artifacts. |
| Published mode, `auto` | Prefer the verified Javadoc JAR and its two companions from the selected release records. |
| Published mode, `source` | Explicitly use tagged source and the generated-output cache, without requesting release Javadoc artifacts. |

The top-level POM property `release.javadoc.schema=1` marks releases whose Javadoc
asset is required in published `auto` mode. A legacy release without that marker
may fall back to source when it advertises no Javadoc artifact. An advertised but
incomplete or invalid artifact set fails validation; a marker-bearing release
without its artifact also fails. Neither case silently substitutes a source build.
Development and untagged pending-release Javadoc always use fresh source.

Downloaded bundles are retained locally under
`website/.cache/release-javadoc/`, keyed by the artifact checksum. Every reuse
copies the cached bundle into private temporary storage and rechecks its checksum,
metadata, and attestation before extraction. These local copies can be
removed and downloaded again; the published release assets are their durable
source. Missing or corrupt local copies are downloaded again; failed published
artifact validation does not fall back to source. Verification can still need
network access for GitHub CLI and trust-root
data, even when the bundle is already local. `source` is useful for an explicit
offline source build or deliberate regeneration; it does not make uncached Maven
dependencies or external Javadoc links available offline.

The published JAR preserves the release's generated bytes when a consumer's JDK
or the current year changes. It is separate from the replaceable generated-output
cache described below. All selected guide versions and rendered-link checks still
run, and the existing `api/<version>/` URLs remain unchanged. Publishing these
GitHub release assets does not run Maven `deploy` or `release:perform`.

## Documentation build cache and timing

The full documentation verification workflow retains every selected documentation
version, generates development Javadoc, and runs the existing executable, release,
and rendered-link checks.
The generated-output cache applies only when Javadoc is built from validated
release source, after release-artifact selection. Current source and
untagged pending-release Javadoc are regenerated on each invocation. Docusaurus
still builds all selected guides together; this optimization does not remove old
versions, change routes, or introduce a separate archive site.

The ignored cache lives at `website/.cache/javadoc/`, outside Maven's `target/`,
so `mvnw clean` leaves it available. Cache mode defaults to `use`:

| Mode | Historical source-build behavior |
| --- | --- |
| `use` | Reuse a valid entry; otherwise generate and cache the result. |
| `refresh` | Regenerate historical Javadoc and replace its cache entries. |
| `off` | Generate historical Javadoc without reading or writing the cache. |

Select the mode with Maven's `docsite.javadocCache` property:

```sh
./mvnw -B -ntp -Pdocsite -Ddocsite.javadocSource=source -Ddocsite.javadocCache=refresh verify
```

These cache modes do not override a verified release artifact selected by `auto`.
Use `docsite.javadocSource=source` when the intent is to regenerate release API
pages, or combine it with `docsite.javadocCache=off` to bypass both forms of reuse.

For direct Python use, all four actions accept `--javadoc-cache use|refresh|off`.
An explicit option overrides `DOCSITE_JAVADOC_CACHE`, which otherwise supplies the
mode. Other values are argument errors and exit with status 2. Maven passes its
property through that environment variable. For example,
after frontend bootstrap:

```sh
./scripts/uv run --locked python scripts/docs_site.py api --javadoc-source source --javadoc-cache off
```

Release selection and tag/POM agreement are checked before looking in the cache.
Entries identify the immutable source commit, current wrapper and actual
Maven/JDK toolchain, generator and configuration digests, artifact-resolution
settings, platform, and UTC year used in the copyright footer. An entry includes
the complete generated file inventory and checksums, including the API index,
search page, and element list. An incompatible, incomplete, or corrupt entry
causes regeneration. Complete
entries are written atomically; a cache-write failure is reported without
discarding successfully generated documentation. A generation failure remains a
build failure and preserves the previous complete generated API output.

The cache is replaceable build output, not release evidence or durable storage.
CI can restore generated entries, but shared entries are saved only by pushes to
`main` and manually dispatched deployments from `main`. Automatic `workflow_run`
deployments restore entries without saving them. A newly published release may
therefore regenerate source-built Javadoc until a later `main` push or manual
deployment warms the shared cache. Verified release artifacts bypass that
generated-output cache. A restored entry must still pass local input and integrity
validation; PR-produced output does not become trusted publication input.
Use `source` with `refresh` when investigating source-generation discrepancies or
deliberately refreshing externally fetched documentation resources. Removing the
generated-output cache makes the next source build regenerate historical Javadoc;
it does not remove a published release artifact.

`website/build-timings.json` records the Python documentation run's applicable
stages: release selection, executable documentation, release metadata, per-version
Javadoc with output-selection/cache outcomes, Docusaurus, and rendered-link checks.
The report includes the selected `javadocSource`; `release-asset` and
`release-asset-cache` identify verified downloaded and locally retained bundles.
The separate `website/npm-ci-timing.json` records dependency installation with its
own timestamps.
These are ignored local reports; use their invocation timestamps to distinguish
current results from an earlier run. Dependency installation is not folded into a
later Python run's duration. Neither report measures the outer Maven packaging,
frontend bootstrap, cache transfer, or artifact upload. GitHub Actions step
durations provide those workflow-level measurements. Both workflows upload the
available reports in a timing artifact, separate from the site, with seven-day
retention. Timing reports are diagnostic;
existing validation gates determine whether the build passes.

### Compare cold and warm builds

Use the same checkout, release selection, toolchain, site mode, and Maven options
for both runs. Set `-Ddocsite.javadocSource=source` in both to measure the generated
cache rather than release-artifact reuse. Keep Java tests either enabled for both
or explicitly skipped for both, and state that choice with the results. Run the
checks serially and preserve each
timing report before the next invocation overwrites it.

1. Run with `-Ddocsite.javadocCache=refresh` to regenerate historical Javadoc and
   populate entries. Record this as a Javadoc-cache-cold run; it does not imply
   that Maven dependencies or npm download caches were cold.
2. Repeat with `-Ddocsite.javadocCache=use`, confirming historical cache hits and
   fresh development generation in the report. Compare per-stage durations and
   repeated runs before attributing a speedup to the cache.
3. Compare release manifests, selected routes, and generated content between the
   runs. Both must pass the same executable documentation and complete rendered
   link/fragment checks. Investigate output differences before claiming equivalence.

Report whole-build and stage timings separately. Historical generation can shrink
while Docusaurus compilation, full-tree validation, and published site size still
grow with the number of retained versions. No performance threshold replaces a
correctness gate. Measure release-artifact download and verification separately
when evaluating published `auto` builds; that is a different comparison from
generated-cache reuse.

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
GitHub CLI with access to read releases and workflow runs and push the
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
Python commands must match the checkout they document: current guides use
`./scripts/uv run python3` (or the equivalent wrapped `python`), while the 3.2.x
checkouts predate that wrapper and retain their direct `python3` instructions
with a historical-tooling notice. Preserve historical audit evidence when updating
command guidance.
For a broken site deployment, revert the responsible docs/configuration change on
`main` and dispatch deployment again. Removing an unpinned software release changes
its eligibility for public version selection while retaining its source snapshot.
Once a version is pinned in the guide archive catalog, missing or reclassified
software release metadata and changed tags fail deployment instead of silently
retiring its published documentation.

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
