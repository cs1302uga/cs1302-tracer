# Maintain guide archives

This guide covers building, verifying, publishing, and correcting retained guide
applications. For ordinary software downloads, see
[installation](../guides/installation.md). A `docs-*` publication contains guide
artifacts; it is separate from a `vX.Y.Z` software release.

## Archived guide applications

The **Maven** property `docsite.guides` defaults to `all` and maps to `DOCSITE_GUIDES`;
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
this workflow does not run guest examples or generate **Javadoc**.

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

The Pages deployment workflow explicitly selects `active` mode. Local Maven builds
and ordinary documentation CI still default to `all`. The
[initial documentation publication](https://github.com/cs1302uga/cs1302-tracer/releases/tag/docs-e6b1d44ba9e8112af0fe1c99d99e6d7a1ba05038)
on 2026-10-17 archived all seven retained stable versions, from 3.2.1 through 4.1.0,
using reviewed builder commit `e6b1d44ba9e8112af0fe1c99d99e6d7a1ba05038`.
`website/guide-archives.json` pins those verified publications, including the two
guides that remain active. The initial published active-mode build verified all
eight guide/API versions and 143 guide routes before this deployment configuration
was enabled. Catalog updates and deployment mode are separate reviewed changes.

For later archive additions or corrections:

1. Use the workflow and builder from reviewed `main`. Local
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
5. Keep `DOCSITE_GUIDE_MODE` in `.github/workflows/docs-deploy.yml` set to `active`.
   Before each later release leaves the two-release active window, publish its
   archive and review its catalog entry. Failure remains a failure; an explicit reviewed
   switch to `all` is the source-rendering recovery option. That switch still validates
   software release identities and retains every version pinned in the catalog.

Correct historical guides through a reviewed snapshot change, a new builder's archive
publication, and a reviewed replacement catalog entry. This preserves URLs while
recording the documentation correction separately from the original software commit.
Archived images, styles, and renderer output remain the verified bytes in their
published archive. Correcting shared assets or the renderer for an archived version
requires a new archive revision and a reviewed catalog pointer; later global asset
changes do not propagate automatically into existing archives.

After [installing the pinned frontend dependencies](documentation-builds.md#local-checks),
inspect a local candidate with:

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
