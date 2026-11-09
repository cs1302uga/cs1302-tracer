# Maven CLI launcher

Build this artifact through the repository root. Maven Invoker copies this project
into `target/maven-plugin`, substitutes the root project version in its POM, and
installs the tracer into `target/launcher-repository` before building the launcher.
The checked-in POM is a template, not a standalone build entry point.

```sh
./mvnw -B -ntp package
```

That command runs the launcher's unit tests, real Maven integration tests, style
checks, and 100% production line/branch coverage. It includes calls from directories
without POMs and with an unrelated POM. The integration tests use the isolated
repository and instrument the actual Maven process for coverage. Root `-DskipTests`
skips both launcher test suites; required Java CI and release validation run them.
To repeat only launcher validation after packaging the tracer:

```sh
./mvnw -B -ntp invoker:run
```

For local use, install the generated plugin into that same isolated repository:

```sh
./mvnw -B -ntp -f target/maven-plugin/pom.xml \
  -Dmaven.repo.local="$PWD/target/launcher-repository" \
  -Dtracer.root="$PWD" -DskipTests install
```

From another directory, invoke the fully qualified plugin goal and add
`-Dmaven.repo.local=/absolute/path/to/target/launcher-repository`.
Use the root project's version in the goal coordinate. Stable published versions
resolve from Central without this local-repository option.

See the [installation guide](../docs/guides/installation.md#run-with-maven)
for consumer commands and the [CLI reference](../docs/reference/cli.md#maven-launcher)
for arguments, streams, and failure behavior. See the
[release runbook](../docs/contributing/releases.md#maven-central-publication)
for signing, frozen bundles, and publication verification.
