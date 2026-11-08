# Development and quality gates

Run commands from the repository root. Use a full JDK; CI tests Java 21 and 25 on Linux and macOS. `.java-version` selects JDK 25 locally. The tracer is compiled for Java 21; guest features depend on the active JDK.

Use `./mvnw` on Unix or `mvnw.cmd` on Windows. The official Wrapper 3.3.4 scripts
select Maven 3.10.0 and verify the distribution's SHA-256 checksum. The build
requires Maven 3.10.0 or later and JDK 21 or later; Enforcer checks both at
`validate`. CI and the pre-push hook use the wrapper. A separate Windows job
bootstraps the wrapper and compiles production and test sources on Java 21 and 25;
the full execution/fixture matrix remains Linux and macOS.

To regenerate the scripts, use
`./mvnw org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.10.0 -Dtype=only-script`.
Review both scripts, retain the executable bit on `mvnw`, and retain or update
`distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties` after verifying
the distribution against the published checksum. Test bootstrap with an empty
`MAVEN_USER_HOME` before adopting a new Maven distribution.



### Building the Project

Before running Maven `clean`, preserve needed audit evidence under `target/`.
Run guest-executing tests, fixture verification, and executable documentation checks
serially; their temporary-directory checks can interfere across concurrent runs.

```bash
# Compile and run unit tests with JaCoCo verification
./mvnw -B -ntp clean test

# Build fat JAR with all dependencies bundled
./mvnw -B -ntp package -DskipTests
```

Compilation treats warnings as errors. Checkstyle uses a 120-character line limit,
requires explicit local types and qualified instance access where applicable, and
rejects star imports. The exact checks and source scope are defined in
`src/main/resources/cs1302_checks_extended.xml` and `pom.xml`.

Every nonempty named class or interface body requires a blank line immediately
after its opening brace and immediately before its closing brace. This includes
enum classes, record classes, annotation interfaces, and nested or local
declarations. Empty bodies, including bodies containing only comments, may omit
this padding. The existing restriction on consecutive blank lines still applies.

Maven runs `picocli-codegen` as an annotation processor, using the same
`picocli.version` as the runtime library. It checks command annotations during
compilation and writes Picocli reflection, resource, and proxy configuration under
`META-INF/native-image/picocli-generated/cs1302uga/cs1302-tracer/` in the JARs.
The processor is a build dependency only; it is not bundled with the application.
This metadata does not establish native-image support for the tracer's compiler,
JDI debugger, guest JVMs, or other dependencies. Normal JVM execution is unchanged.

### JaCoCo Coverage Requirement

The JaCoCo check enforces **100% line coverage** and **100% branch coverage** for the configured `cs1302.tracer.internal.model` and `cs1302.tracer.internal.serialize` packages. It does not enforce that threshold across the tracing engine or all production packages. New lifecycle tests exercise real subprocesses with outer deadlines and cleanup; coverage percentage alone is not the release gate.

### Python script quality

The dedicated Scripts quality CI job uses the checked-in uv wrapper, managed
Python 3.15, and locked dependencies. It requires Ruff lint/format checks, ty, and
100% line and branch coverage for every production Python script under `scripts/`.
Tests live under `scripts/tests/` and exercise callable interfaces and CLIs with
real temporary files, Git repositories, and local HTTP servers. External process,
network, and shutdown failures use controlled boundary substitutes.

Run from the repository root:

```sh
./scripts/uv sync --locked
./scripts/uv run --locked ruff check scripts
./scripts/uv run --locked ruff format --check scripts
./scripts/uv run --locked ty check scripts
export COVERAGE_RCFILE="$PWD/scripts/pyproject.toml"
export COVERAGE_FILE="$PWD/.coverage"
./scripts/uv run --locked coverage erase
./scripts/uv run --locked coverage run -m unittest discover -s scripts/tests -t scripts
./scripts/uv run --locked coverage combine
./scripts/uv run --locked coverage report
```

Child-process coverage and temporary script copies contribute to the same report;
test files and local tool environments are excluded from production measurement.
Release creation depends on this job. Java hooks retain their existing behavior.

### Dependency upgrade policy

Prefer stable releases compatible with the Java 21 target. Keep Picocli and its
processor aligned, and keep both JavaParser artifacts aligned. The October 2026
upgrade recorded in commit `9edd36a` selected ASM 9.11 and Checkstyle 14.3.0.
`pom.xml` is authoritative for current versions. That audit's upstream availability
assessment is historical; verify availability again before the next upgrade.

The dependency-management overrides for Guava 33.7.2-jre and Javassist 3.33.0-GA
update JavaParser's support libraries. Error Prone annotations 2.50.0 and JSpecify
1.0.1 align shared annotation dependencies across Guava, Gson, and JUnit. JSON-java
20260814 and Byte Buddy 1.18.15 update JSONassert/AssertJ transitives and remain
test-only. Reassess these overrides when upgrading their owning libraries; do not
add them as new direct application dependencies merely to control their versions.

JSONassert 2.0-rc1 remains an existing prerelease exception: the October 2026
audit found stable 1.5.3 would be a downgrade and no final 2.x release was available
at that time. Defer AssertJ 4 milestones, Maven 4 and plugin previews, and Checker
Framework qualifiers 4.x until separately validated. Plugin-owned libraries
(including JaCoCo's ASM) are a separate dependency graph; prefer upgrading the
owning plugin to overriding its internals. A newer version number alone does not
establish compatibility or a security defect in the currently selected version.

For each upgrade, compare resolved dependencies, run the appropriate focused
checks and full coverage gate, verify committed trace fixtures on Java 21 and 25,
and rebuild the docs/site. Do not refresh expected JSON to accommodate an
unexplained behavior change. Check the automatic module name, bundled dependency
descriptors, Picocli metadata, and third-party license notices after packaging.

### Running Example Traces

The `examples/` directory includes 34 single-stack examples (`example0` through `example33`) covering basic primitives, multi-file packages, loops, lambdas, instance methods, stdin streaming, pointer aliasing, varargs, unbuffered standard output, standard error capture, uncaught runtime exceptions, generic lists with autoboxing, active call stack frames, polymorphic generic container reification, multi-level class inheritance with dynamic dispatch, empty string and zero-length array instances, all numeric and non-numeric primitive wrapper class types, java.awt.Color objects with transparency and aliasing, guest standard input reading with java.util.Scanner, and JDK-dependent features with java.lang.IO.

To regenerate all example outputs:

```bash
./examples/generate_all.sh
```

Examples `example34` through `example41` cover multithread tracing: start/join,
synchronized and unsynchronized shared state, wait/notify, executor shutdown,
worker exceptions, daemon lifetime, and timeout. Their READMEs contain runnable
commands. `examples/concurrent-batch/jobs.ndjson` demonstrates independent jobs
with configurable result ordering. These examples are exercised by Java
integration tests using structural and lifecycle assertions; they do not have
golden trace files because thread schedules vary between runs.

---


## Pre-commit lint and coverage gates


The executable `.githooks/pre-commit` runs Checkstyle first, then coverage.
It does not automatically reformat or re-stage files.

`.githooks/pre-commit-checkstyle` exports the Git index to a fresh temporary
workspace and runs `./mvnw -B -ntp checkstyle:check`. This uses the staged Maven
configuration and `src/main/resources/cs1302_checks_extended.xml`, with the same
source scope as the normal build. Partial staging, spaces, and quotes in filenames
are preserved. Style violations or tool failures block the commit before coverage
runs. The check runs even without staged Java changes, so configuration changes
are checked too. To check the working tree manually, run `./mvnw checkstyle:check`.

The opt-in `pre-commit-coverage` Maven profile requires **100% line and branch
coverage across all production classes**, in addition to the normal build checks.
Run it directly with `./mvnw -Ppre-commit-coverage clean test`.

The executable `.githooks/pre-commit-coverage` exports the Git index into a temporary
workspace and runs that command there. It tests staged content only, excludes stale
coverage data, cleans up afterward, and never formats, stages, or changes files.
Stage related build configuration with the change. A failing build, test,
or coverage gate blocks the commit. After running the profile locally, open
`target/site/jacoco/index.html` to inspect line and branch coverage.

To enable both checks in a checkout using Git's default hooks directory:

```sh
cat > "$(git rev-parse --git-path hooks)/pre-commit" <<'EOF'
#!/bin/sh
set -eu
exec "$(git rev-parse --show-toplevel)/.githooks/pre-commit" "$@"
EOF
chmod +x "$(git rev-parse --git-path hooks)/pre-commit"
```

This enables lint and coverage without activating the separate pre-push hook.
Installation is local to each checkout; the scripts and
Maven profile are tracked in the repository. Git permits local hooks to be bypassed;
use the same Maven profile in CI if this must also be a merge requirement.

## Compatibility fixtures

After packaging, run:

```sh
./scripts/uv run python -m unittest discover -s examples -p test_verify.py
./scripts/uv run python examples/verify.py
```

Regenerate fixtures only intentionally with `./scripts/uv run python examples/verify.py --update`, then review the diff. The verifier normalizes object identities while preserving values, aliasing, cycles, and step order.

See [documentation maintenance](documentation.md) for site checks and release preparation.

See [security analysis](security-analysis.md) for CodeQL regression coverage and alert dispositions.

## Supported Java interface checks

`ApiExposureTest` scans compiled supported declarations and rejects third-party or
internal types in public/protected signatures, generic bounds, inheritance, and
annotations, with a narrow exception for API Guardian's `@API` and its status
metadata. It requires explicit `STABLE` annotations on supported types and
`INTERNAL` on public implementation types, including nested types. It also
compiles an external consumer with only tracer classes and API Guardian on the
compile classpath. `TracerTest` exercises detached outcomes, failures, budgets,
breakpoint discovery, and consecutive batch jobs through the supported interface.
Runtime dependencies remain required for execution. Run these with
`./mvnw -B -ntp -Djacoco.skip=true -Dtest=ApiExposureTest,TracerTest test`.
