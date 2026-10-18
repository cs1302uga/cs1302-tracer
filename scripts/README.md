# Repository support scripts

These scripts check **Maven** version ownership, validate tracer documentation, build
the versioned documentation site and **Javadoc**, and orchestrate signed releases.
Executable documentation checks require `./mvnw -B -ntp package` first. In addition
to CLI and reference examples, they extract the binary ZIP and run the first-trace,
Java integration, and two-job batch tutorials using the installed **JDK**.
Run the examples below from the repository root unless stated otherwise.

The Python test suite also exercises the **Bash** launcher under
`src/main/scripts/code-tracer.sh`, including Java selection, relocation, argument
boundaries, and error behavior. CI separately smoke-tests the packaged archive
with real JDKs.

On Windows with **PowerShell 7.4+** and a full JDK on `PATH` (or selected with
`JAVA_HOME`), run the Windows launcher checks from the repository root:

```powershell
Install-Module Pester -RequiredVersion 6.2.0 -Scope CurrentUser -Repository PSGallery -Force
Install-Module PSScriptAnalyzer -RequiredVersion 1.25.0 -Scope CurrentUser -Repository PSGallery -Force
Import-Module Pester -RequiredVersion 6.2.0
Import-Module PSScriptAnalyzer -RequiredVersion 1.25.0
Invoke-ScriptAnalyzer src/main/scripts/code-tracer.ps1
Invoke-Pester -Path scripts/tests/WindowsLauncher.Tests.ps1 -CI
```

Treat any analyzer diagnostic as a failure, as CI does. The **Pester** tests compile a
Java probe in a temporary directory and verify actual subprocess behavior. On
macOS/Linux, they exercise PowerShell and skip the eleven **Command Prompt** tests;
only native Windows runs validate the batch launcher. Windows CI additionally
extracts the packaged ZIP and runs real traces with both launchers on JDK 21/25.

## Maven repository setup

`./scripts/uv run --locked python scripts/configure_maven.py` merges the shared
JVNet-to-Central mirror into user Maven settings. An optional path selects another
settings file. Changed existing settings receive a backup beside the original;
credentials and unrelated settings are retained. See the
[development guide](../docs/contributing/development.md#legacy-jvnet-repository-workaround)
for the verified dependency path, scope, and removal criteria. CI uses the same
script after `setup-java`; native Windows runners use `setup-python` because the
shell wrapper below supports Windows through WSL only.

## Setup and command wrappers

Use the checked-in `scripts/uv` and `scripts/uvx` symlinks. Both invoke
[`uvw.sh`](uvw.sh), which downloads and caches the pinned **uv** release when needed.
No globally installed uv or Python is required for bootstrap. The wrapper supports
Linux and macOS, and Windows through WSL; it requires Bash and the download/archive
utilities listed in its header.

```sh
./scripts/uv sync --locked
./scripts/uv run python --version
```

The scripts project requires Python 3.15 or later and currently pins 3.15 in
[`.python-version`](.python-version). To explicitly set that pin and synchronize:

```sh
./scripts/uv python pin 3.15 --directory scripts
./scripts/uv sync
```

The wrapper defaults to the scripts project, managed Python, and
`UV_SYSTEM_PYTHON=0`; explicit flags and environment overrides take precedence.
It preserves your working directory, so script paths remain relative to where you
run the command. From inside `scripts/`, use `./uv run python docs_release.py check`
instead of `./scripts/uv run python scripts/docs_release.py check`.

Use `./scripts/uv run` for project commands and `./scripts/uvx` for isolated tools.
For Python commands, use `./scripts/uv run python3 SCRIPT [ARGS...]` from the
repository root. The `python` spelling in existing wrapped examples selects the
same managed interpreter. Invoke either spelling through the wrapper so the
command uses the scripts project's Python version and dependencies.
Invoking `./scripts/uvw.sh` directly selects uv. Without the wrapper, the equivalent
root-level command form is `uv run --project scripts --managed-python ARGS...`.
See [`pyproject.toml`](pyproject.toml) and [`uv.lock`](uv.lock) for dependencies.

The wrapper's binary cache is `scripts/.uv/`; it is separate from uv's package
cache. `UVW_OFFLINE=1` prevents wrapper downloads and requires cached binaries;
package resolution has its own offline setting. For automation, set
`UVW_GIT_COMMIT=never` to disable the wrapper's optional commit offers. See the
wrapper header for version overrides, checksums, cache configuration, and self-update
behavior.

## Script catalog

| Script | Purpose |
| --- | --- |
| [`check_pom.py`](check_pom.py) | Check version properties, plugin ownership, and aligned test-scoped JsonUnit dependencies offline. Defaults to the repository POM; accepts an optional POM path. Exit codes: 0 passed, 1 policy violations, 2 input/argument errors. |
| [`check_docs.py`](check_docs.py) | Validate Markdown destinations, executable help, JSON examples, tutorials, and CLI recipes. Requires the packaged tracer JAR and binary ZIP. Checks local/heap relationships, capture modes, input, sources, dependencies, presentation, and partial results. `--update-cli` refreshes the CLI reference. |
| [`docs_site.py`](docs_site.py) | Run `build`, `start`, `api`, or complete `verify` with explicit preview/published selection, Javadoc artifact/source and cache controls, and stage timings. |
| [`build_javadoc.py`](build_javadoc.py) | Select verified release Javadoc or tagged-source output and generate development API pages. Invoke through `docs_site.py api`. |
| [`javadoc_cache.py`](javadoc_cache.py) | Internal historical-output cache with build-input fingerprints and complete-file integrity checks. Configure it through `docs_site.py`. |
| [`javadoc_artifact.py`](javadoc_artifact.py) | Validate release Javadoc structure and manifest metadata with `check`, writing its checksum; `verify` also checks the adjacent checksum and attestation bundle against the trusted release identity. |
| [`javadoc_publish.py`](javadoc_publish.py) | Preserve a complete verified remote Javadoc set before upload and verify actual remote files afterward; reject partial sets rather than replacing them. See the release runbook for CLI arguments and recovery. |
| [`docs_timing.py`](docs_timing.py) | Record documentation stage durations and completion status. Its `npm-ci` action runs locked frontend installation and writes a separate timing report. |
| [`check_site.py`](check_site.py) | Check rendered links, fragments, base paths, and version-matched Javadoc links in `website/build`. |
| [`serve_docs.py`](serve_docs.py) | Serve an existing build with its project base path and intact Javadoc URLs. Defaults to `127.0.0.1:3000`; accepts `--host` and `--port`. |
| [`docs_release.py`](docs_release.py) | Validate releases with `check`, create snapshots with `prepare --version X.Y.Z` while retaining the snapshot POM, and export the legacy `published` selection report. `check --pending X.Y.Z` checks snapshot agreement before the stable version transition; `check --tag vX.Y.Z` remains stable-only. |
| [`release.py`](release.py) | Prepare signed Maven release history in an isolated clone; publish its exact commits through CI and verify assets/deployment. Provides `prepare`, `resume`, `publish`, saved-report `status`, and the workflow's `ci` branch check. |
| [`release_validate.py`](release_validate.py) | Maven lifecycle hook that preserves stale Java coverage and runs the serial local release gates. The stable POM, strict Java coverage, and docsite profiles are required. |
| [`release_evidence.py`](release_evidence.py) | Read-only evidence library for exact push-run matrix results, clean tagged JAR metadata, archive contents, and live documentation provenance. Used by `release.py publish`. |
| [`rehearse_docs_release.py`](rehearse_docs_release.py) | Exercise release preparation, publishing selection, retained archives, retries, and missing tags using real builds in a disposable project. |
| [`check_external_links.py`](check_external_links.py) | Check external HTTP links and write `external-links-report.md`; remote availability is advisory rather than a required PR gate. |
| [`tests/`](tests/) | Behavioral tests for all Python script interfaces, release workflows, generated documentation, local HTTP services, and external-tool failure handling. |

## Build and preview documentation

Use a full JDK and local release tags. The Maven profile installs pinned
**Node.js 24.22.0 LTS** with bundled **npm 11.19.1**, installs locked site dependencies,
and runs executable docs, metadata, Javadoc, site generation, and rendered-link checks:

```sh
git fetch --tags
./mvnw -B -ntp -Pdocsite verify
./scripts/uv run --locked python scripts/serve_docs.py --port 3100
```

Open `http://127.0.0.1:3100/cs1302-tracer/`. Stop the server with Ctrl-C.
Follow [documentation builds](../docs/contributing/documentation-builds.md) for live
preview and published mode using the plugin-installed toolchain. See the
[release runbook](../docs/contributing/releases.md#release-rehearsal) for rehearsal.

`docs_site.py verify` runs the complete documentation checks after tracer packaging;
`build` generates the site, `start` runs the development server, and `api` generates
only Javadoc and the release manifest. `--mode` selects `preview` or `published`;
`--releases PATH` supplies the JSON release records required in published mode.
Retain complete asset descriptors for automatic Javadoc artifact selection,
including names, sizes, download URLs, and SHA-256 digests.
Defaults come from `DOCSITE_MODE` (otherwise `preview`) and `DOCSITE_RELEASES`
(otherwise no records). The Maven profile passes these through environment variables.
Explicit CLI options override the corresponding defaults.
For an untagged release whose following development commit has already advanced
the POM, preview can pin its stable ancestor with `--pending-commit FULL_SHA` or
`DOCSITE_PENDING_COMMIT`; Maven forwards `-Ddocsite.pendingCommit=FULL_SHA`.
The source commit and prepared/current docs must agree. This override is rejected
in published mode, and it does not create or publish a release tag.
CI bootstraps the pinned Node toolchain before release-context validation, including
the `main` build between its next-development fast-forward and the explicit tag
push. Its early `initialize` phase installs the toolchain; the later `verify`
installs npm dependencies once. Local editing still uses `generate-resources`
before starting the server. The pending stable commit must be the next-development
commit's direct parent.

The build owns `website/build`; output/site/configuration overrides are rejected.
`start` accepts `--host`, `--port`/`-p`, and `--no-open`; `start` and `build` accept
`--no-minify`. `verify` accepts no forwarded bundler flags. The `build:maven` npm
script invokes the managed Python wrapper itself. Other npm scripts use `python3`
from their inherited environment; invoke them through `./scripts/uv run --locked`.
Generating a local site does not publish it.

All actions accept `--javadoc-source auto|source`, defaulting to
`DOCSITE_JAVADOC_SOURCE` or `auto`. An explicit option takes precedence; Maven
forwards `-Ddocsite.javadocSource=auto|source`. Preview uses tagged source without
release-artifact downloads. Published `auto` prefers the selected release's verified
Javadoc JAR, checksum, and attestation bundle. A marker-free legacy release that
advertises no artifact can fall back to source; missing required artifacts
or incomplete/invalid advertised sets fail. `source` explicitly bypasses release
artifacts. Downloaded bundles live under `website/.cache/release-javadoc/` and are
verified on every reuse, which can still require network access for trust data.
See [published Javadoc artifacts](../docs/contributing/javadoc-artifacts.md#published-javadoc-artifacts)
for provenance requirements and the `javadoc_artifact.py check`/`verify` commands.

Source-built historical Javadoc uses the ignored `website/.cache/javadoc/`
directory, which survives Maven `clean`. All actions accept `--javadoc-cache use|refresh|off`:
`use` reuses valid entries or generates missing ones, `refresh` regenerates and
replaces them, and `off` generates without cache access. The explicit option
overrides `DOCSITE_JAVADOC_CACHE`; the default is `use`. Maven forwards
`-Ddocsite.javadocCache=use|refresh|off` through that variable. These modes apply
only to source builds; combine `--javadoc-source source` and `--javadoc-cache off`
to bypass both artifact and generated-output reuse. Development and
untagged pending Javadoc always regenerate. Cache use preserves tag/POM validation
and the complete `verify` workflow's documentation and rendered-link checks.

`website/build-timings.json` records Python stages and per-version cache outcomes;
`website/npm-ci-timing.json` separately records the timed dependency installation.
Check their invocation timestamps and preserve reports before comparing runs.
They exclude outer Maven packaging, frontend bootstrap, and workflow transfers.
See [cache and timing guidance](../docs/contributing/documentation-builds.md#documentation-build-cache-and-timing)
for input validation, CI trust, failure handling, and controlled cold/warm comparisons.

## Signed release workflow

Follow the [release runbook](../docs/contributing/releases.md#prepare-a-stable-release)
for version classification, signing prerequisites, pending snapshots, and the
full command sequence. **Maven Release Plugin 3.3.1** owns version transitions and
tagging; **GitHub Actions** publishes assets. Do not use `release:perform`.

`release.py prepare` requires explicit `--release-version`, `--development-version`,
and `--change-kind major|minor|patch`, plus a nonexistent `--directory` for its
isolated clone. Its source must be clean and equal the current `origin/main`, with
a matching `X.Y.Z-SNAPSHOT` POM and committed pending documentation snapshot.
The authenticated **GitHub CLI** supplies published-version and CI evidence.
Existing trusted SSH signing configuration is required for both generated commits
and the annotated tag. Preparation first rehearses POM/SCM transformations, then
runs the full release gates; it pushes no refs.

After review and explicit publication authorization, `release.py publish
--directory PATH` pushes the stable and next-development commits successively to
the same release branch, requiring CI on each exact revision, then fast-forwards
`main` and explicitly pushes the tag. It returns 3 while CI or publication evidence
is pending; rerun the same command to continue. Exit 0 means assets and live docs
were verified, while exit 1 means the operation stopped with an error. Generated
history is never squashed or rebased, and published tags are never moved.
Retries accept later `main` descendants containing the exact prepared history.
Every required CI job must pass in the latest attempt, including the
`Regression gates` aggregate and every original matrix job. The aggregate
supplements the individual checks in exact-revision release evidence. A partial
rerun without the complete matrix is insufficient; rerun all jobs before retrying
publication. A revision predating the aggregate cannot gain it through a rerun.
For previously prepared history, preserve its recorded revisions and follow the
[recovery guidance](../docs/contributing/releases.md#recover-according-to-the-recorded-and-remote-state).
The publisher preserves complete verified Javadoc assets on retries and never
automatically overwrites them. A partial artifact set requires the runbook's
explicit recovery procedure; a final check verifies the actual remote files.

`status --directory PATH` only prints the saved `.git/code-tracer-release.json`
report; it does not refresh remote state. `resume --directory PATH` handles only
interrupted real preparation before remote publication. Inspect the report, Maven
state, remote refs, and `.git/release-logs/` before recovery. See the runbook's
[recovery procedure](../docs/contributing/releases.md#recover-according-to-the-recorded-and-remote-state).

## Tests and development tools

```sh
./scripts/uv run python -m unittest discover -s scripts/tests -t scripts
./scripts/uv run ruff check scripts
./scripts/uv run ruff format --check scripts
./scripts/uv run ty check scripts
```

**Ruff** lint and formatting, **ty**, and 100% production Python line and branch coverage
are enforced by the Scripts quality CI job. Release creation depends on this job.
Local **Git** hooks check staged whitespace and Java style. Python quality remains
part of required CI and explicit local validation. Run the coverage gate from the root:

```sh
export COVERAGE_RCFILE="$PWD/scripts/pyproject.toml"
export COVERAGE_FILE="$PWD/.coverage"
./scripts/uv run --locked coverage erase
./scripts/uv run --locked coverage run -m unittest discover -s scripts/tests -t scripts
./scripts/uv run --locked coverage combine
./scripts/uv run --locked coverage report
```

The coverage configuration measures all production scripts, includes child
processes, and maps identical copies in temporary projects back to their source.
Tests use real files, Git, and local HTTP servers, with controlled stand-ins for
external build tools. Run guest-executing Java tests, trace fixture verification,
and `check_docs.py` serially because temporary-directory checks can interfere.

Prefer inline PEP 723 dependency metadata when an individual script needs external
Python packages, then run it as `./scripts/uv run path/to/script.py`. Keep shared
development tools in `pyproject.toml`. See [`AGENTS.md`](AGENTS.md) for repository
script conventions and the [development guide](../docs/contributing/development.md)
for Java quality gates and compatibility checks.
