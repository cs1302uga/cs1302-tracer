# Repository support scripts

## Running commands

Use `./scripts/uv` from the repository root, or `./uv` from inside `scripts/`.
It is a symlink to `uvw.sh`, which bootstraps the pinned uv release and defaults
to the scripts project, managed Python, and `UV_SYSTEM_PYTHON=0`. Explicit flags
and environment overrides take precedence. The wrapper preserves the working
directory, so script paths remain relative to where you invoke it.

`./scripts/uvx` uses the same wrapper to dispatch to the pinned uvx executable
for isolated tool execution. Use `./scripts/uv run` for this project's installed
tools and dependencies. Direct `./scripts/uvw.sh` invocation behaves like uv.

From the repository root:

```sh
./scripts/uv python pin 3.15 --directory scripts
./scripts/uv sync
./scripts/uv run python scripts/docs_release.py check
./scripts/uv run python -m unittest discover -s scripts/tests -t scripts
./scripts/uv run ruff check scripts
./scripts/uv run ruff format --check scripts
./scripts/uv run ty check scripts
```

The wrapper supplies the usual `--project`, `--managed-python`, and environment
defaults; omit those repeated options in normal commands. Consult `pyproject.toml`,
`.python-version`, and `uv.lock` for project configuration. Read the header of
`uvw.sh` when changing the uv pin, cache, offline behavior, or self-update flow;
it also documents optional Git commit prompts and `UVW_GIT_COMMIT=never`.

When a script needs external Python dependencies, prefer inline PEP 723 metadata
(`# /// script` blocks) in that script so its requirements
travel with it. Keep shared development tools in `pyproject.toml`. Run scripts
with inline metadata through `./scripts/uv run path/to/script.py` so uv resolves
their declared dependencies.

## Available scripts

| File | Purpose |
| --- | --- |
| `check_docs.py` | Checks Markdown links and executable CLI, quickstart, JSON, and batch examples. Requires the built tracer JAR. `--update-cli` rewrites the CLI reference. |
| `check_site.py` | Validates links, fragments, base paths, and version-matched API links in `website/build`. Run after building the site. |
| `check_external_links.py` | Checks external HTTP links and writes `external-links-report.md`; remote failures are advisory rather than a PR merge gate. |
| `docs_release.py` | Validates and prepares snapshots; owns release selection, routes, tag provenance, and explicit pending-commit previews. `prepare --version` retains the snapshot POM; `check --pending` validates before its stable transition. |
| `docs_site.py` | Entry point for `build`, `start`, `api`, and `verify`; owns release selection, Javadoc source/cache controls, and stage timings. `verify` retains executable docs, metadata, and rendered-link checks. |
| `build_javadoc.py` | Stages fresh development Javadoc and verified release artifacts or tagged-source output. Invoke through `docs_site.py api`. |
| `javadoc_cache.py` | Owns historical-output fingerprints, complete-entry verification, and cache storage; configure through `docs_site.py`. |
| `javadoc_artifact.py` | Owns release Javadoc structure, manifest/checksum checks, and trusted attestation verification. `check` writes a checksum; `verify` requires the adjacent release companions. |
| `javadoc_publish.py` | Preserves verified remote Javadoc bytes on retries and verifies the uploaded set. Partial sets require reviewed recovery; never replace them automatically. |
| `guide_archive.py` | Validates guide ZIPs, catalog descriptors, and main-branch attestations before private extraction. |
| `guide_build.py` | Selects active guides, builds archive candidates, and composes historical applications into the site. |
| `guide_publish.py` | Checks the clean main builder, preserves complete archive sets, and proposes catalogs from verified remote files. |
| `docs_timing.py` | Records stages for one documentation invocation; `npm-ci` measures dependency installation separately. |
| `serve_docs.py` | Serves an existing build with project-base paths and unchanged Javadoc URLs. Supports `--host` and `--port`. |
| `rehearse_docs_release.py` | Exercises preparation, publishing, archive retention, retries, and missing tags using real builds in a disposable project. |
| `release.py` | Owns isolated signed preparation, resumable publication through exact CI revisions, saved reports, and release-branch validation. |
| `release_validate.py` | Runs the serial local release gate around Maven's strict Java and documentation profiles; retains stale coverage as audit evidence. |
| `release_evidence.py` | Verifies exact push-run matrix results and published JAR/archive/documentation provenance without publishing or waiting. |
| `tests/` | Behavioral tests for release selection and preparation, build CLI, executable documentation, rendered links, HTTP checks, preview serving, and rehearsal. |

## Required Python checks

Tests live in `scripts/tests/`. Use the script CLI or callable interfaces used by
other scripts. Prefer real temporary files, Git repositories, and local HTTP
servers; substitute only external process, network, time, or shutdown boundaries.
Do not mock internal collaborators or weaken checks to satisfy a percentage.

From the repository root, run lint, formatting, and type checking with the commands
above, then run the full coverage sequence:

```sh
export COVERAGE_RCFILE="$PWD/scripts/pyproject.toml"
export COVERAGE_FILE="$PWD/.coverage"
./scripts/uv run --locked coverage erase
./scripts/uv run --locked coverage run -m unittest discover -s scripts/tests -t scripts
./scripts/uv run --locked coverage combine
./scripts/uv run --locked coverage report
```

Completion requires all checks to pass and every production Python script to have
100% line and branch coverage. Coverage includes child processes and combines
identical script copies from temporary projects; tests and local tool environments
are outside the production measurement. Keep `source_pkgs` in `pyproject.toml`
current when adding scripts so copied CLI execution remains measured. Always
erase stale data and combine child-process data before reporting coverage.
The Scripts quality CI job uses these checks and blocks release creation on
failure. Existing Git hooks continue to run Java checks.


## Documentation workflow

For site builds, performance/cache changes, release preparation, or publishing changes, read
[`docs/contributing/documentation.md`](../docs/contributing/documentation.md).
Site tooling needs a full JDK and local release tags for historical APIs. The opt-in
Maven profile provisions pinned Node/npm and runs the complete documentation checks.
The release runbook also defines the required signing trust, semantic version
classification, isolated prepare/publish phases, and recovery rules. Preserve its
distinction between a saved report and freshly verified local/remote evidence.
Keep release evidence checks fail-closed: missing or skipped CI jobs, unexpected
refs, mismatched artifact metadata, and unpublished documentation do not establish
publication. Pending source overrides are preview-only and must retain exact
commit/documentation provenance. Tests must exercise real temporary Git history
and substitute only external build, network, and process boundaries.
For cache changes, preserve provenance validation before reuse and complete
rendered checks afterward. Keep published Javadoc artifacts distinct from generated
caches; a cache hit or checksum alone does not establish trusted release provenance.
Preserve required-artifact failures and the explicit source-build override described
in the guide. For performance claims, follow the guide's controlled
cold/warm comparison and report the measured stages separately from whole-build time.

```sh
./mvnw -B -ntp -Pdocsite verify
./scripts/uv run python scripts/serve_docs.py --port 3100
```

Run guest-executing Java tests, trace fixtures, and `check_docs.py` serially:
they can interfere through shared temporary compilation directories. Preserve
trace formats and committed fixture expectations when changing scripts.
