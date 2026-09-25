# Development and quality gates

Run commands from the repository root. Use a full JDK; CI tests Java 21 and 25 on Linux and macOS. `.java-version` selects JDK 25 locally. The tracer is compiled for Java 21; guest features depend on the active JDK.



### Building the Project

```bash
# Compile and run unit tests with JaCoCo verification
./mvnw -B -ntp clean test

# Build fat JAR with all dependencies bundled
./mvnw -B -ntp package -DskipTests
```

### JaCoCo Coverage Requirement

The JaCoCo check enforces **100% line coverage** and **100% branch coverage** for the configured `cs1302.tracer.model` and `cs1302.tracer.serialize` packages. It does not enforce that threshold across the tracing engine or all production packages. New lifecycle tests exercise real subprocesses with outer deadlines and cleanup; coverage percentage alone is not the release gate.

### Running Example Traces

The `examples/` directory contains 34 reference test cases (`example0` through `example33`) covering basic primitives, multi-file packages, loops, lambdas, instance methods, stdin streaming, pointer aliasing, varargs, unbuffered standard output, standard error capture, uncaught runtime exceptions, generic lists with autoboxing, active call stack frames, polymorphic generic container reification, multi-level class inheritance with dynamic dispatch, empty string and zero-length array instances, all numeric and non-numeric primitive wrapper class types, java.awt.Color objects with transparency and aliasing, guest standard input reading with java.util.Scanner, and JDK-dependent features with java.lang.IO.

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


The executable `.githooks/pre-commit` runs Checkstyle first, then coverage. It
replaces the old formatter hook: nothing is automatically reformatted or re-staged.

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
Stage the updated `pom.xml` when first adding the hook. A failing build, test,
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
python3 -m unittest discover -s examples -p test_verify.py
python3 examples/verify.py
```

Regenerate fixtures only intentionally with `python3 examples/verify.py --update`, then review the diff. The verifier normalizes object identities while preserving values, aliasing, cycles, and step order.

See [documentation maintenance](documentation.md) for site checks and release preparation.

See [security analysis](security-analysis.md) for CodeQL regression coverage and alert dispositions.
