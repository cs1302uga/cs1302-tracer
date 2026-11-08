# Read the API documentation offline

The release **Javadoc** bundle contains the API reference, navigation, and search
files. Download and verify it while online, then extract it and open `index.html`
in a browser. Links to other projects can still require network access. This is
documentation: do not run the Javadoc JAR with `java -jar` or use it as a library.

## Choose a release and download its files

Use a [GitHub release](https://github.com/cs1302uga/cs1302-tracer/releases) that
provides all three matching assets:

- `cs1302-tracer-X.Y.Z-javadoc.jar`
- `cs1302-tracer-X.Y.Z-javadoc.jar.sha256`
- `cs1302-tracer-X.Y.Z-javadoc.jar.sigstore.json`

These assets are included by the current release workflow; older releases may
not have them. In particular, **v4.1.0 does not provide a Javadoc bundle**. For a
release without the bundle, select its version on the
[documentation site](https://cs1302uga.github.io/cs1302-tracer/) and follow
**API Reference** to read its online API documentation instead.

Save the three files together in a new directory. You also need **GitHub CLI**
(`gh`) with the verification options below, and a **JDK** with `jar` on your
`PATH`. No source checkout or publishing privileges are required.

Check the CLI before starting:

```sh
gh --version
gh attestation verify --help
```

The recipe requires `--bundle`, `--hostname`, `--repo`, `--signer-workflow`,
`--signer-digest`, `--source-digest`, `--source-ref`, `--deny-self-hosted-runners`,
and `--predicate-type`. These options were checked with GitHub CLI 2.102.0;
an earlier version works only if it supplies every required option. Upgrade the
CLI if the subcommand or an option is missing. Keep the verification options;
removing an unsupported check weakens the provenance policy.

## Find the release commit

In the commands below, replace `X.Y.Z` with the stable version without its `v`
prefix. Replace `FULL_RELEASE_COMMIT_SHA` with the full 40-character lowercase
commit SHA identified by that release's `vX.Y.Z` tag on GitHub. Use the commit the
tag points to, not an annotated tag object's SHA. Confirm this expected identity
from the project's release/tag history; do not copy it from the downloaded JAR
or attestation as a substitute for choosing which release to trust.

Resolve the selected tag to its commit with:

```sh
gh api repos/cs1302uga/cs1302-tracer/commits/vX.Y.Z --jq .sha
```

The output must be one 40-character lowercase hexadecimal commit SHA. Alternatively,
open the selected release on GitHub, follow the commit link beside its tag, and
copy the full commit SHA. For an annotated tag, the Git refs API and an unpeeled
`git ls-remote` result identify the tag object, which is a different object from
the commit. The commits API above resolves the tag to the required commit.

## Verify, then extract

Run one of these blocks from the directory containing the three downloaded files.
The checksum checks the downloaded bytes; the attestation binds those bytes to
this repository's `build.yml` publisher, the expected tag and source/signer
commit, and a GitHub-hosted runner. A checksum alone does not establish that
provenance. The flags follow [GitHub CLI's attestation verification interface](https://cli.github.com/manual/gh_attestation_verify).

Both blocks stop before extraction if a check fails and require a new `api`
directory. Keep the verification flags intact. Verification can still need
network access for trust data even with the Sigstore bundle on disk.

### Bash on Linux or macOS

```bash
(
  set -euo pipefail
  version='X.Y.Z'
  commit='FULL_RELEASE_COMMIT_SHA'
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$commit" =~ ^[0-9a-f]{40}$ ]] || {
    printf '%s\n' 'Replace the version and full release commit placeholders.' >&2
    exit 1
  }
  jar="cs1302-tracer-$version-javadoc.jar"
  if command -v sha256sum >/dev/null; then
    actual=$(sha256sum "$jar")
  else
    actual=$(shasum -a 256 "$jar")
  fi
  [[ "$(cat "$jar.sha256")" == "$actual" ]] || {
    printf '%s\n' 'Javadoc checksum mismatch.' >&2
    exit 1
  }
  gh attestation verify "$jar" --bundle "$jar.sigstore.json" \
    --hostname github.com --repo cs1302uga/cs1302-tracer \
    --signer-workflow cs1302uga/cs1302-tracer/.github/workflows/build.yml \
    --signer-digest "$commit" --source-digest "$commit" \
    --source-ref "refs/tags/v$version" --deny-self-hosted-runners \
    --predicate-type https://slsa.dev/provenance/v1
  mkdir api
  (cd api && jar --extract --file "../$jar")
  printf '%s\n' 'Open api/index.html in your browser.'
)
```

### PowerShell on Windows

```powershell
& {
    $ErrorActionPreference = 'Stop'
    $version = 'X.Y.Z'
    $commit = 'FULL_RELEASE_COMMIT_SHA'
    if ($version -cnotmatch '^[0-9]+\.[0-9]+\.[0-9]+$' -or
        $commit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Replace the version and full release commit placeholders.'
    }
    $jar = "cs1302-tracer-$version-javadoc.jar"
    $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksum = Get-Content -LiteralPath "$jar.sha256" -Raw
    if ($checksum -cne "$hash  $jar`n") { throw 'Javadoc checksum mismatch.' }
    gh attestation verify $jar --bundle "$jar.sigstore.json" `
        --hostname github.com --repo cs1302uga/cs1302-tracer `
        --signer-workflow cs1302uga/cs1302-tracer/.github/workflows/build.yml `
        --signer-digest $commit --source-digest $commit `
        --source-ref "refs/tags/v$version" --deny-self-hosted-runners `
        --predicate-type https://slsa.dev/provenance/v1
    if ($LASTEXITCODE -ne 0) { throw 'Javadoc provenance verification failed.' }
    New-Item -ItemType Directory -Path api | Out-Null
    Push-Location api
    try {
        jar --extract --file "../$jar"
        if ($LASTEXITCODE -ne 0) { throw 'Javadoc extraction failed.' }
    } finally {
        Pop-Location
    }
    Write-Output 'Open api/index.html in your browser.'
}
```

Open `api/index.html` after the block succeeds. Keep the extracted directory
together so its pages, search files, and styles remain available offline.

For additional archive-structure, embedded version/commit, and toolchain-metadata
validation using the repository's verifier, see the advanced
[Javadoc artifact maintenance guide](../contributing/javadoc-artifacts.md).
That helper checks more than the standalone attestation command; neither a local
metadata check nor a successful extraction replaces provenance verification.
