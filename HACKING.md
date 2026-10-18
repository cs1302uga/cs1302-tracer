# Contributing to cs1302-tracer

Use a full **JDK 21 or 25** and **Git**. The scripts wrapper provisions managed Python
3.15 and the locked development tools. `.java-version` selects JDK 25.
The **Maven** wrapper supplies Maven. Run these commands from the repository root:

```sh
./mvnw -B -ntp package
./scripts/uv run python -m unittest discover -s examples -p test_verify.py
./scripts/uv run python examples/verify.py
```

The normal build runs **Checkstyle** and tests, and enforces 100% line/branch coverage
for the configured model and serializer packages. The optional
`./mvnw -Ppre-commit-coverage clean test` profile requires 100% line/branch coverage
across all production classes. It is stricter than the normal CI gate.
Before running Maven `clean`, preserve any needed audit evidence under `target/`.
Run Java tests, trace fixture verification, and executable documentation checks
serially because they share temporary-directory checks.

- [Development guide](docs/contributing/development.md#everyday-workflow): choose checks for your change, then configure local hooks, coverage, and example regeneration.
- [Architecture](docs/contributing/architecture.md): compilation, tracing, serialization, and lifecycle.
- [Documentation maintenance](docs/contributing/documentation.md): checks, release snapshots, deployment, and retries.

## Python tooling

Run `./scripts/uv sync --locked`, then follow the
[scripts quality commands](scripts/README.md#tests-and-development-tools).
CI enforces **Ruff** lint/format checks, **ty**, and 100% production Python line/branch
coverage. Tests live under `scripts/tests/`; subprocess coverage is combined before
reporting. The scripts CI job is required for releases; Git hooks remain Java-only.

## Documentation changes

Build and validate the site with the opt-in Maven profile:

```sh
./mvnw -B -ntp -Pdocsite verify
```

It provisions **Node.js 24.22.0 LTS** and bundled **npm 11.19.1** locally, runs the
locked frontend install, and checks executable examples, release metadata, **Javadoc**,
the site build, and rendered links. Normal Maven builds do not require Node/npm.
For live preview, bootstrap and select the local tools:

```sh
./mvnw -B -ntp -Pdocsite generate-resources
export PATH="$PWD/target/frontend/node:$PATH"
./scripts/uv run --locked npm --prefix website start
```

See [documentation builds](docs/contributing/documentation-builds.md) for prerequisites
and published-mode builds, and the [release runbook](docs/contributing/releases.md#release-rehearsal)
for release rehearsal. Python tooling changes also require
the [scripts quality gates](scripts/README.md#tests-and-development-tools).

For CLI changes, regenerate help with `./scripts/uv run python scripts/check_docs.py --update-cli`
and review the diff. Keep README and HACKING concise; detailed guides belong in
`docs/`, which the site renders directly. Review prose when behavior changes even
if automated checks pass.

First-party contributions are covered by the repository's [MIT license](LICENSE).
