# Development and quality gates

Run commands from the repository root. Use a full JDK; CI tests Java 21 and 25 on Linux and macOS. `.java-version` selects JDK 25 locally. The tracer is compiled for Java 21; guest features depend on the active JDK.

Use `./mvnw` on Unix or `mvnw.cmd` on Windows. The official Wrapper 3.3.4 scripts
select Maven 3.10.0 and verify the distribution's SHA-256 checksum. The build
requires the Maven version selected by the wrapper or later, and JDK 21 or later;
Enforcer checks both at `validate`. CI and the pre-push hook use the wrapper.
A separate Windows job
bootstraps the wrapper, packages the project, and checks both Windows launchers on Java 21 and 25;
the full execution/fixture matrix remains Linux and macOS.

To regenerate the scripts, use
`./mvnw org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.10.0 -Dtype=only-script`.
Review both scripts, retain the executable bit on `mvnw`, and retain or update
`distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties` after verifying
the distribution against the published checksum. Test bootstrap with an empty
`MAVEN_USER_HOME` before adopting a new Maven distribution.


At `validate`, Properties Maven Plugin reads the wrapper properties, then
Build Helper extracts the Maven version from `distributionUrl`, and Enforcer
uses that version as an inclusive lower bound, with no upper bound. Keep those
plugin executions in that order. Missing wrapper properties or an unrecognized
distribution filename fail validation.
The URL must end in `apache-maven-VERSION-bin.zip` or
`apache-maven-VERSION-bin.tar.gz`. Updating the wrapper URL and checksum updates
the requirement automatically; there is no second Maven version pin in the POM.
Direct plugin goals do not automatically run `validate`; prepend `validate` when
the toolchain check is required (for example, `./mvnw validate checkstyle:check`).

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

Packaging produces `target/code-tracer.jar` for local CLI use and release downloads.
It is a byte-for-byte copy of `target/code-tracer-jar-with-dependencies.jar`;
the copy runs after assembly and refreshes the short filename on every package run.
The original assembly remains attached with the `jar-with-dependencies` classifier,
and the ordinary `cs1302-tracer-VERSION.jar` remains Maven's main artifact.
Keep the copy execution after assembly so installation and deployment retain
those artifact identities. The source archive is unchanged.

During `prepare-package`, AntRun copies `code-tracer.sh`, `code-tracer.ps1`, and
`code-tracer.cmd` from `src/main/scripts/` without filtering. It removes the old
generated `target/code-tracer.bat` and refreshes the extensionless `code-tracer`:
a relative symlink to `code-tracer.sh` on Unix, or a regular copy on native Windows.
Bash uses executable permissions, LF endings, and Bash 3.2-compatible syntax;
PowerShell uses LF and requires 7.4+; Command Prompt uses CRLF and invokes Java
directly. The `.cmd` copy normalizes CRLF during packaging.

The ZIP assembly runs after `assemble-jar`. The `unix-launcher-archive` profile,
automatically active on Unix, additionally assembles the tar.gz with the prepared
relative symlink. Native Windows creates only the ZIP and needs no symlink
privileges for packaging. `src/assembly/bin-common.xml` supplies the shared JAR,
named launchers, and license; `bin.xml` adds a regular extensionless Bash copy to
the ZIP, and `bin-tar.xml` preserves the symlink without content transformations.
Both archives attach with classifier `bin` and distinct types. The Linux release
job publishes both. Neither archive replaces a JAR artifact.

Bash subprocess tests live in `scripts/tests/test_launcher.py`; PowerShell and
batch subprocess tests live in `scripts/tests/WindowsLauncher.Tests.ps1` and use
a small Java probe to inspect received arguments, working directory, streams,
and exit status. Windows CI runs Pester 6.2.0 and PSScriptAnalyzer 1.25.0.
The Java 21/25 Linux/macOS and Windows matrices extract their respective archives
and execute real traces from paths containing spaces. Windows runs launcher
checks rather than the full Java test/fixture suite. Tests isolate
`CODE_TRACER_JAR_DIR` from the developer environment and cover absolute, relative,
empty, literal, and invalid overrides. Archive smoke tests also move the JAR out
of the launcher directory. See [installation](../guides/installation.md#store-the-jar-separately)
for the environment-variable contract.

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

### Dependency versions and upgrades

`pom.xml` is authoritative for selected versions. Put every explicitly selected
library, BOM, transitive override, annotation processor, and build-plugin version
in a named `*.version` property. Group library/BOM properties separately from
build tools. Use one property for artifacts released together: JavaParser's two
libraries, Picocli and its processor, and JsonUnit's two test APIs. JUnit artifacts
inherit from the JUnit BOM; do not add individual version pins to them.

Keep direct dependencies in `dependencies`, and BOM imports and justified
transitive overrides in `dependencyManagement`. Put all plugin versions in the
top-level `pluginManagement`; active plugins and profiles inherit those versions.
Declare the Checkstyle engine dependency once in the managed Checkstyle plugin.
Project release versions and Enforcer's Java compatibility range remain literal.
Enforcer's Maven lower bound is derived at runtime from the wrapper distribution.

Run the offline structural policy check from the repository root:

```sh
./scripts/uv run --locked python scripts/check_pom.py
```

An optional positional POM path checks a different file. Exit status is 0 for a
passing policy, 1 for violations, and 2 for an unreadable/malformed file or invalid
arguments. The Scripts quality CI job runs this check before its quality gates.
It checks version-property references, plugin ownership, the single Checkstyle
engine declaration, and matching test-scoped JsonUnit declarations. It does not
query releases or prove dependency compatibility; those require the upgrade audit
and execution checks below.

Choose the latest compatible stable releases while preserving Java 21 support.
Do not adopt milestones, betas, release candidates, or alternate artifact variants
as automatic upgrades. Review release notes and the resolved graph, including
transitive dependencies. Reassess overrides when upgrading their owning libraries;
do not add unused direct dependencies merely to control versions. Plugin-owned
libraries are a separate graph: prefer upgrading the owning plugin to overriding
its internals.

The 2026-10-17 audit retained already-current stable direct dependencies and build
plugins, replaced JSONassert with test-scoped JsonUnit 6.3.0, and upgraded the
JavaParser transitive `checker-qual` from 3.55.1 to 4.3.1. This is a dated assessment,
not a claim that those versions will remain latest. The qualifier JAR uses Java 11
bytecode and does not install or run the Checker Framework annotation processor.

The Guava, Javassist, Checker Qual, Error Prone annotations, JSpecify, and Byte
Buddy overrides remain explicit. Byte Buddy stays test-only. JSONassert and its
JSON-java override have been removed. JsonUnit uses the existing Gson parser;
its APIs and additional transitives remain test-only. The migrated CLI assertions
allow extra object fields, require array order and length, and compare numbers
mathematically with zero tolerance. Focused negative checks protect those rules.

For each upgrade, compare resolved dependencies and effective plugin configuration,
run the focused checks and full coverage gate, verify committed trace fixtures on
Java 21 and 25, and rebuild the docs/site. Do not refresh expected JSON to accommodate
an unexplained behavior change. Check the automatic module name, bundled dependency
descriptors, Picocli metadata, and third-party license notices after packaging.

### Legacy JVNet repository workaround

Checkstyle plugin 3.6.0 brings in `javax.xml.bind:jaxb-api:2.3.1` through its
JDK 9+ profile. Its parent `jaxb-api-parent:2.3.1` inherits `net.java:jvnet-parent:5`,
which declares `jvnet-nexus-releases` at `https://maven.java.net/content/repositories/releases/`.
That endpoint presented an expired certificate during the 2026-10-17 audit.
Checkstyle 3.6.0 was already the latest stable plugin release. The JAXB API and
its Activation API 1.2.0 dependency, their parent POMs, and the complete Checkstyle
plugin graph were verified available on Maven Central using an empty local cache.

The shared `.mvn/jvnet-mirror.xml` redirects only repository ID
`jvnet-nexus-releases` to `https://repo.maven.apache.org/maven2`.
To apply it to local user settings, run from the repository root:

```sh
./scripts/uv run --locked python scripts/configure_maven.py
```

An optional settings-file path configures another file. The command merges the
mirror into `~/.m2/settings.xml`, retaining credentials, profiles, and unrelated
mirrors. Before changing an existing file it creates an owner-only
`settings.xml.backup-*` beside it and prints that path; repeated runs with the
same mirror leave the file unchanged. Conflicting mirrors with the same mirror
ID or exact repository ID are replaced. Existing wildcard mirrors remain intact;
Maven gives this exact repository-ID match precedence. On Unix, newly written
settings and backups have mode 0600. Restore the printed backup to undo a change.

Every Maven CI job invokes `.github/actions/configure-maven` after `setup-java`
generates settings, including Windows packaging and documentation deployment.
This merges the same definition into runner user settings. Installation-level
settings and TLS certificate validation remain unchanged. Other contributors
must run the local command themselves; Maven does not automatically load the
checked-in mirror fragment. Reassess this workaround when upgrading Checkstyle;
remove it once the resolved plugin graph no longer introduces the legacy repository.

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
