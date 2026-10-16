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
./scripts/uv run python -m unittest discover -s scripts -p 'test_*.py'
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
| `docs_release.py` | Validates and prepares snapshots; owns release selection, routes, and tag provenance. Provides `check`, `prepare`, and the legacy `published` selection report. |
| `docs_site.py` | Entry point for `build`, `start`, and `api`; generates Javadoc and release manifests. Defaults to preview mode; published mode requires `--mode published --releases PATH`. |
| `build_javadoc.py` | Library for staged API generation from working sources and pinned release archives. Invoke through `docs_site.py api`. |
| `serve_docs.py` | Serves an existing build with project-base paths and unchanged Javadoc URLs. Supports `--host` and `--port`. |
| `rehearse_docs_release.py` | Exercises preparation, publishing, archive retention, retries, and missing tags using real builds in a disposable project. |
| `test_docs_release.py` | Tests snapshot validation, release agreement, selection, and retention. |
| `test_release_selection.py` | Tests release plans and tag provenance using real temporary files and Git repositories. |
| `test_docs_site.py` | Tests CLI argument handling, pinned sources, manifest ownership, and failures; only external Maven/npm commands use stand-ins. |

## Documentation workflow

For site builds, release preparation, or publishing changes, read
[`docs/contributing/documentation.md`](../docs/contributing/documentation.md).
Site tooling needs Node/npm, a full JDK, and local release tags for historical APIs.

```sh
./scripts/uv run python scripts/docs_site.py build
./scripts/uv run python scripts/check_site.py
./scripts/uv run python scripts/serve_docs.py --port 3100
```

Run guest-executing Java tests, trace fixtures, and `check_docs.py` serially:
they can interfere through shared temporary compilation directories. Preserve
trace formats and committed fixture expectations when changing scripts.
