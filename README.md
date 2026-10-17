<!--
SPDX-FileCopyrightText: Copyright (c) 2024-2025 Michael E. Cotterell and the University of Georgia
SPDX-License-Identifier: MIT
-->

# cs1302-tracer

Compile Java programs, trace execution with the Java Debug Interface, and inspect
stack frames and heap objects as Python Tutor or modern JSON.

For instructors running examples and developers integrating trace output into tools.

## Run a release

Use a **full stable JDK 21 or later**. Download `code-tracer.jar` from the
[latest stable software release](https://github.com/cs1302uga/cs1302-tracer/releases/latest),
then run this command in the directory containing the JAR:

```sh
java -jar code-tracer.jar --help
```

Continue with [your first trace](docs/tutorials/first-trace.md), which creates its
own Java example and needs no source checkout. Use the documentation version
matching your downloaded release; `/next/` describes development builds.
The [installation guide](docs/guides/installation.md) explains JDK setup, available
assets, and shell-specific launchers. Software releases have `vX.Y.Z` tags;
separate `docs-*` publications contain historical guide archives.

Trusted local examples can run directly. **The tracer does not sandbox programs.**
Hosted student submissions require whole-job isolation as described in the
[runner contract](docs/RUNNER_CONTRACT.md). Ordinary tracing has finite default
budgets; omitted limits in envelope and batch jobs are unlimited.

## Build and run

Build from source to develop or modify the tracer. CI tests JDK 21 and 25; guest
programs using newer features need a matching JDK. The **Maven** wrapper downloads
Maven automatically:

```sh
git clone https://github.com/cs1302uga/cs1302-tracer.git
cd cs1302-tracer
./mvnw -B -ntp clean package
```

The executable from a source build is `target/code-tracer.jar`; the packaged
launchers provide **Bash**, **PowerShell**, and **Command Prompt** entry points.
From this checkout, run:

<!-- quickstart:start -->
```sh
java -jar target/code-tracer.jar trace \
  -i examples/example0/Driver.java --all-breakpoints --format modern --pretty
```
<!-- quickstart:end -->

This prints a JSON trace of a record instance, including source text, execution
steps, stack frames, and heap objects. `trace` is an explicit subcommand; run
`java -jar target/code-tracer.jar --help` to list commands.

Programs may use configured binary directories and JARs through repeatable
`--class-path`/`-cp` options, including quoted patterns such as `lib/**/*.jar`.
Java integrations use `TraceClassPath`; batch requests configure dependencies per
job. See [binary dependencies](docs/reference/java-api.md#binary-dependencies) and
the [tracing guide](docs/guides/tracing.md#add-binary-dependencies) for ordering,
manifest handling, optional copying, and resource ownership.
The [binary dependency example](examples/classpath/README.md) builds a small
library JAR and traces a program using a recursive class-path pattern.

## Documentation

- [Documentation site](https://cs1302uga.github.io/cs1302-tracer/) — published release guides and **Javadoc**, with development documentation under `/next/`.
- [Installation](docs/guides/installation.md) and [first trace tutorial](docs/tutorials/first-trace.md).
- [Read a trace](docs/tutorials/read-a-trace.md), [batch tutorial](docs/tutorials/batch-jobs.md), and [Java integration tutorial](docs/tutorials/java-integration.md).
- [CLI reference](docs/reference/cli.md), [JSON output](docs/reference/output.md), and [batch protocol](docs/reference/batch.md).
- [Limits and result envelopes](docs/BOUNDED_TRACING.md) and [troubleshooting](docs/guides/troubleshooting.md).
- [HACKING](HACKING.md) — contributor setup and quality gates.

Historical guide versions retain their existing URLs. Maintainers can follow
[guide archive maintenance](docs/contributing/guide-archives.md) to publish or
update those versions.

## License

First-party code and documentation are [MIT licensed](LICENSE).
Dependency notices retain their original terms. Display bundled dependency notices
with `java -jar target/code-tracer.jar show-licenses`.
