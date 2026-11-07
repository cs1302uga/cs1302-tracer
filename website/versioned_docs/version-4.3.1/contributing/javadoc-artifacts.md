# Maintain Javadoc artifacts

This guide covers **Javadoc** release artifacts and their use in the documentation build.
To download and read a release's API reference without a source checkout, use
[the offline API guide](../guides/offline-javadoc.md).

## Published Javadoc artifacts

The opt-in `release-javadoc` **Maven** profile attaches
`target/cs1302-tracer-VERSION-javadoc.jar` with the standard `javadoc` classifier
at `package`. It contains the public API reference, including its index, search,
and supporting files. Ordinary `package` builds do not create this artifact.
The release publisher builds the profile from the clean stable tagged commit and
publishes three files together:

- `cs1302-tracer-VERSION-javadoc.jar`
- `cs1302-tracer-VERSION-javadoc.jar.sha256`
- `cs1302-tracer-VERSION-javadoc.jar.sigstore.json`

The checksum identifies the exact JAR bytes. The JAR's metadata identifies its
version, clean source commit, generator, **JDK** version/vendor, and Maven version.
The Sigstore bundle carries the SLSA build-provenance attestation created by the
pinned `actions/attest` release step. Verification uses **GitHub CLI** to require
the expected repository and publisher workflow, matching signer and source commit,
the `refs/tags/vVERSION` ref, and a GitHub-hosted runner. A checksum alone, or a
signed **Git** tag without verified artifact provenance, does not establish that
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

All documentation actions (`start`, `build`, `api`, and `verify`) accept
`--javadoc-source auto|source`. The default is
`auto`; an explicit option overrides `DOCSITE_JAVADOC_SOURCE`. Maven forwards
`-Ddocsite.javadocSource=auto|source` through that variable. Other values are
argument errors and exit with status 2.

See [version selection and build modes](documentation-builds.md#version-selection-and-build-modes)
for the preview/published mode options and release-record input.

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
[cache described in the build guide](documentation-builds.md#documentation-build-cache-and-timing).
All selected guide versions and rendered-link checks still run, and the existing
`api/<version>/` URLs remain unchanged. Publishing these
GitHub release assets does not run Maven `deploy` or `release:perform`.
