# Repository support scripts

These scripts check Maven version ownership, validate tracer documentation, build
the versioned documentation site and Javadoc, and prepare documentation releases.
Executable documentation checks require `./mvnw -B -ntp package` first. In addition
to CLI and reference examples, they extract the binary ZIP and run the first-trace,
Java integration, and two-job batch tutorials using the installed JDK.
Run the examples below from the repository root unless stated otherwise.

The Python test suite also exercises the Bash launcher under
`src/main/scripts/code-tracer.sh`, including Java selection, relocation, argument
boundaries, and error behavior. CI separately smoke-tests the packaged archive
with real JDKs.

On Windows with PowerShell 7.4+ and a full JDK on `PATH` (or selected with
`JAVA_HOME`), run the Windows launcher checks from the repository root:

```powershell
Install-Module Pester -RequiredVersion 6.2.0 -Scope CurrentUser -Repository PSGallery -Force
Install-Module PSScriptAnalyzer -RequiredVersion 1.25.0 -Scope CurrentUser -Repository PSGallery -Force
Import-Module Pester -RequiredVersion 6.2.0
Import-Module PSScriptAnalyzer -RequiredVersion 1.25.0
Invoke-ScriptAnalyzer src/main/scripts/code-tracer.ps1
Invoke-Pester -Path scripts/tests/WindowsLauncher.Tests.ps1 -CI
```

Treat any analyzer diagnostic as a failure, as CI does. The Pester tests compile a
Java probe in a temporary directory and verify actual subprocess behavior. On
macOS/Linux, they exercise PowerShell and skip the eleven Command Prompt tests;
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
[`uvw.sh`](uvw.sh), which downloads and caches the pinned uv release when needed.
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
| [`check_docs.py`](check_docs.py) | Validate Markdown destinations, executable CLI help, quickstart commands, JSON examples, and the batch example. Requires the built tracer JAR. `--update-cli` refreshes the CLI reference. |
| [`docs_site.py`](docs_site.py) | Run `build`, `start`, or `api` with explicit preview/published release selection; generate Javadoc and the release manifest. |
| [`build_javadoc.py`](build_javadoc.py) | Internal generation library for working-source and pinned historical Javadoc. Invoke through `docs_site.py api`. |
| [`check_site.py`](check_site.py) | Check rendered links, fragments, base paths, and version-matched Javadoc links in `website/build`. |
| [`serve_docs.py`](serve_docs.py) | Serve an existing build with its project base path and intact Javadoc URLs. Defaults to `127.0.0.1:3000`; accepts `--host` and `--port`. |
| [`docs_release.py`](docs_release.py) | Validate releases with `check`, create snapshots with `prepare`, and export the legacy `published` selection report. |
| [`rehearse_docs_release.py`](rehearse_docs_release.py) | Exercise release preparation, publishing selection, retained archives, retries, and missing tags using real builds in a disposable project. |
| [`check_external_links.py`](check_external_links.py) | Check external HTTP links and write `external-links-report.md`; remote availability is advisory rather than a required PR gate. |
| [`tests/`](tests/) | Behavioral tests for all Python script interfaces, release workflows, generated documentation, local HTTP services, and external-tool failure handling. |

## Build and preview documentation

Install Node.js 22 or later and a full JDK meeting the repository's Java 21 baseline.
Historical Javadoc generation requires local release tags. Follow
[documentation maintenance](../docs/contributing/documentation.md) for the complete
release and deployment workflow.

```sh
./mvnw -B -ntp package -DskipTests
./scripts/uv run npm --prefix website ci
git fetch --tags
./scripts/uv run python scripts/check_docs.py
./scripts/uv run python scripts/docs_release.py check
./scripts/uv run python scripts/docs_site.py build
./scripts/uv run python scripts/check_site.py
./scripts/uv run python scripts/serve_docs.py --port 3100
```

Open `http://127.0.0.1:3100/cs1302-tracer/`. Stop the server with Ctrl-C.
Use `docs_site.py start` for the development server or `docs_site.py api` to generate
only Javadoc and the release manifest. All three actions default to preview mode.
Published mode requires `--mode published --releases PATH`, where `PATH` contains a
JSON array of GitHub release records. The build owns `website/build`; output/site/
configuration overrides are rejected. `start` accepts `--host`, `--port`/`-p`, and
`--no-open`; `start` and `build` accept `--no-minify`.

The website's npm scripts invoke `python3` from their inherited environment.
Use `./scripts/uv run npm --prefix website ...` to select the required managed
Python runtime. CI uses this wrapper form with `--locked` for documentation builds
and script checks. Generating the local site does not publish it.

## Tests and development tools

```sh
./scripts/uv run python -m unittest discover -s scripts/tests -t scripts
./scripts/uv run ruff check scripts
./scripts/uv run ruff format --check scripts
./scripts/uv run ty check scripts
```

Ruff lint and formatting, ty, and 100% production Python line and branch coverage
are enforced by the Scripts quality CI job. Release creation depends on this job.
The existing Git hooks remain Java-only. Run the coverage gate from the root:

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
