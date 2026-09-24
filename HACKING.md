# Contributing to cs1302-tracer

Use a full JDK 21 or 25, Git, and Python 3.9 or later. `.java-version` selects JDK 25.
The Maven wrapper supplies Maven. Run these commands from the repository root:

```sh
./mvnw -B -ntp clean package
python3 -m unittest discover -s examples -p test_verify.py
python3 examples/verify.py
```

The normal build runs Checkstyle and tests, and enforces 100% line/branch coverage
for the configured model and serializer packages. The optional
`./mvnw -Ppre-commit-coverage clean test` profile requires 100% line/branch coverage
across all production classes. It is stricter than the normal CI gate.

- [Development guide](docs/contributing/development.md): local hooks, coverage, and example regeneration.
- [Architecture](docs/contributing/architecture.md): compilation, tracing, serialization, and lifecycle.
- [Documentation maintenance](docs/contributing/documentation.md): checks, release snapshots, deployment, and retries.

## Documentation changes

Node.js 22 or later is needed only for the documentation site. CI uses Node.js 22.

```sh
npm --prefix website ci
npm --prefix website start
```

Before submitting a change, build the JAR and run:

```sh
python3 scripts/check_docs.py
python3 -m unittest discover -s scripts -p 'test_*.py'
npm --prefix website run build
python3 scripts/check_site.py
```

For CLI changes, regenerate help with `python3 scripts/check_docs.py --update-cli`
and review the diff. Keep README and HACKING concise; detailed guides belong in
`docs/`, which the site renders directly. Review prose when behavior changes even
if automated checks pass.

First-party contributions are covered by the repository's [MIT license](LICENSE).
