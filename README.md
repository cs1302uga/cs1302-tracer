<!--
SPDX-FileCopyrightText: Copyright (c) 2024-2025 Michael E. Cotterell and the University of Georgia
SPDX-License-Identifier: MIT
-->

# cs1302-tracer

Compile Java programs, trace execution with the Java Debug Interface, and inspect
stack frames and heap objects as Python Tutor or modern JSON.

For instructors running examples and developers integrating trace output into tools.

## Build and run

Use a **full JDK 21 or later**. CI tests JDK 21 and 25; guest programs using newer
features need a matching JDK. The Maven wrapper downloads Maven automatically.

```sh
git clone https://github.com/cs1302uga/cs1302-tracer.git
cd cs1302-tracer
./mvnw -B -ntp clean package
```

The executable is `target/code-tracer-jar-with-dependencies.jar`.

<!-- quickstart:start -->
```sh
java -jar target/code-tracer-jar-with-dependencies.jar trace \
  -i examples/example0/Driver.java --all-breakpoints --format modern --pretty
```
<!-- quickstart:end -->

This prints a JSON trace of a record instance, including source text, execution
steps, stack frames, and heap objects. `trace` is an explicit subcommand; run
`java -jar target/code-tracer-jar-with-dependencies.jar --help` to list commands.

Trusted local examples can run directly. **The tracer does not sandbox programs.**
Hosted student submissions require whole-job isolation as described in the
[runner contract](docs/RUNNER_CONTRACT.md). Ordinary tracing has finite default
budgets; envelope and batch jobs require explicit limits.

## Documentation

- [Documentation site](https://cs1302uga.github.io/cs1302-tracer/) — development docs initially; versioned releases after the first documented release.
- [Installation](docs/guides/installation.md) and [first trace tutorial](docs/tutorials/first-trace.md).
- [CLI reference](docs/reference/cli.md), [JSON output](docs/reference/output.md), and [batch protocol](docs/reference/batch.md).
- [Limits and result envelopes](docs/BOUNDED_TRACING.md) and [troubleshooting](docs/guides/troubleshooting.md).
- [HACKING](HACKING.md) — contributor setup and quality gates.

## License

First-party code and documentation are [MIT licensed](LICENSE).
Dependency notices retain their original terms. Display bundled dependency notices
with `java -jar target/code-tracer-jar-with-dependencies.jar show-licenses`.
