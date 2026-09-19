<!--
SPDX-FileCopyrightText: Copyright (c) 2024-2025 Michael E. Cotterell and the University of Georgia
SPDX-License-Identifier: CC-BY-NC-ND-4.0
-->

# cs1302-tracer

Traces the execution of student-submitted Java programs.

- [cs1302-tracer](#cs1302-tracer)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [CLI Reference](#cli-reference)
    - [Global Options](#global-options)
    - [Subcommands](#subcommands)
    - [Common Trace Options](#common-trace-options)
      - [Input & Breakpoint Selection](#input--breakpoint-selection)
      - [Output Formatting & Representation](#output-formatting--representation)
      - [Execution Budgets & Isolation Policy](#execution-budgets--isolation-policy)
      - [Diagnostics & Help](#diagnostics--help)
    - [`batch-trace` Options](#batch-trace-options)
    - [`list-breakpoints` Options](#list-breakpoints-options)
    - [`show-licenses` Options](#show-licenses-options)
  - [Output Formats & Schema](#output-formats--schema)
    - [1. PythonTutor Format (`--format=pytutor`, default)](#1-pythontutor-format---formatpytutor-default)
    - [2. Modern Format (`--format=modern`)](#2-modern-format---formatmodern)
    - [3. JSON Envelope Wrapper (`--result-envelope`)](#3-json-envelope-wrapper---result-envelope)
  - [Execution Limits & Inspection Policies](#execution-limits--inspection-policies)
    - [Execution Limits](#execution-limits)
    - [Inspection Policies](#inspection-policies)
  - [Benchmarking](#benchmarking)
  - [Development & Pre-commit Quality Gate](#development--pre-commit-quality-gate)
    - [Git Hook Setup](#git-hook-setup)
    - [Pre-commit Validation Checks](#pre-commit-validation-checks)
  - [License](#license)

---

## Prerequisites

- **Java Development Kit (JDK)**: Version 21 or later.
- **Maven**: Version 3.8 or later (optional; Maven wrapper `./mvnw` is included).

---

## Installation

Clone the repository and build the executable jar using the included Maven wrapper:

```bash
git clone https://github.com/cs1302uga/cs1302-tracer.git
cd cs1302-tracer
./mvnw clean package -DskipTests
```

The compiled standalone executable JAR will be located at:

```bash
target/code-tracer-jar-with-dependencies.jar
```

You can run it directly with `java -jar` or create a convenient alias:

```bash
alias code-tracer="java -jar $(pwd)/target/code-tracer-jar-with-dependencies.jar"
```

---

## CLI Reference

```text
Usage: code-tracer [-hV] [COMMAND]
Traces the execution of student-submitted Java programs.
```

### Global Options

| Option | Flag | Description |
| :--- | :--- | :--- |
| `--help` | `-h` | Show this help message and exit. |
| `--version` | `-V` | Print version information and exit. |

---

### Subcommands

| Subcommand | Description |
| :--- | :--- |
| `trace` | *(Default)* Compile and trace Java program execution at selected breakpoints. |
| `batch-trace` | High-throughput batch tracing processing NDJSON requests over persistent guest JVM workers. |
| `list-breakpoints` | List valid breakpoint lines for the input source. |
| `show-licenses` | Display licensing information for third-party dependencies. |

---

### Common Trace Options

The following options apply to the default execution command (`trace`):

```text
Usage: code-tracer [trace] [-ahpsvV] [--all-breakpoints]
                         [--accumulate-breakpoints] [--eval-enum-hash]
                         [--format=<format>] [--inline-strings]
                         [--inspection=<inspection>] [-i=<input>]
                         [--max-elements=<maxElements>]
                         [--max-heap-objects=<maxHeapObjects>]
                         [--max-output-bytes=<maxOutputBytes>]
                         [--max-snapshots=<maxSnapshots>]
                         [--max-source-bytes=<maxSourceBytes>]
                         [--max-source-files=<maxSourceFiles>]
                         [--max-trace-bytes=<maxTraceBytes>]
                         [--no-eval-enum-hash] [--pretty]
                         [--remove-main-args] [--remove-method-this]
                         [--result-envelope] [--stdin=<guestStdin>]
                         [--stdin-file=<guestStdinFile>]
                         [--timeout-ms=<timeoutMillis>]
                         [--type-style=<typeStyle>] [-b=<spec>[,<spec>...]]...
```

#### Input & Breakpoint Selection

| Option | Flag | Default | Description |
| :--- | :--- | :--- | :--- |
| `--input=<file>` | `-i` | `stdin` | Input path to Java source file (defaults to `stdin` if omitted). |
| `--stdin=<string>` | | | Literal input string provided to the traced program via standard input. |
| `--stdin-file=<file>` | | | Path to file whose content is provided to the traced program via standard input. |
| `--all-breakpoints` | `-a` | `false` | Include all encountered breakpoint instances in chronological order. |
| `--breakpoints=<spec>` | `-b` | | Breakpoints at which to take snapshots (e.g. `'12'`, `'Main.java:12'`, comma-separated `'12,Helper.java:5'`, or main exit sentinel `'-1'`). Repeatable. |
| `--accumulate-breakpoints` | | `false` | Output an array of snapshots containing each reached breakpoint instance instead of only the last. |

#### Output Formatting & Representation

| Option | Flag | Default | Description |
| :--- | :--- | :--- | :--- |
| `--format=<format>` | `-f` | `pytutor` | Output trace format: `pytutor` or `modern`. |
| `--pretty` | `-p` | `false` | Pretty-print JSON output with indentation. |
| `--type-style=<typeStyle>` | | `fqn` | Type qualification style: `fqn` (e.g. `java.lang.String`) or `simple` (e.g. `String`). |
| `--inline-strings` | `-s` | `false` | Inline string values into fields/variables rather than allocating heap objects. |
| `--remove-main-args` | | `false` | Don't include the `main` method's `args` parameter in the output stack frame. |
| `--remove-method-this` | | `false` | Don't include the `this` reference variable for instance methods in stack frames. |
| `--eval-enum-hash` | | enabled | Evaluate lazy enum hash codes when capturing snapshots (default). |
| `--no-eval-enum-hash` | | | Suppress evaluation of lazy enum hash codes when capturing snapshots. |

#### Execution Budgets & Isolation Policy

| Option | Default | Description |
| :--- | :--- | :--- |
| `--result-envelope` | `false` | Emit versioned job status and trace JSON wrapper. |
| `--unlimited` | `false` | Disable default budgets; explicit limits still apply. |
| `--timeout-ms=<ms>` | `10000` | Tracing deadline in milliseconds (0 is unlimited; default active without envelope/unlimited). |
| `--max-snapshots=<num>` | `10000` | Maximum captured snapshots (0 is unlimited). |
| `--max-output-bytes=<bytes>` | `1048576` (1 MiB) | Retained guest bytes per output stream stdout/stderr (0 is unlimited). |
| `--max-heap-objects=<num>` | `10000` | Distinct reachable object identities per snapshot (0 is unlimited). |
| `--max-elements=<num>` | `100000` | Inspected array slots, fields, and locals per snapshot (0 is unlimited). |
| `--max-trace-bytes=<bytes>` | `67108864` (64 MiB)| Accounted snapshot trace storage bytes (0 is unlimited). |
| `--max-source-bytes=<bytes>` | `1048576` (1 MiB) | Maximum UTF-8 source bytes before parsing (0 is unlimited). |
| `--max-source-files=<num>` | `128` | Maximum streamed source files (0 is unlimited). |
| `--inspection=<inspection>` | `TRUSTED` | Inspection policy: `TRUSTED` (rich helper inspection) or `FIELDS` (invokes no guest methods, requires `--result-envelope`). |

#### Diagnostics & Help

| Option | Flag | Description |
| :--- | :--- | :--- |
| `--verbose` | `-v` | Output messages about what the tracer is doing. |
| `--help` | `-h` | Show help message and exit. |
| `--version` | `-V` | Print version information and exit. |

---

### `batch-trace` Options

```text
Usage: code-tracer batch-trace [-hV] [-i=<input>]
                               [--max-jobs-per-worker=<maxJobsPerWorker>]
                               [-w=<workers>]
```

| Option | Flag | Default | Description |
| :--- | :--- | :--- | :--- |
| `--input=<file>` | `-i` | `stdin` | Input path to NDJSON file (defaults to `stdin` if omitted). |
| `--workers=<workers>` | `-w` | `1` | Number of persistent worker sessions running concurrently. |
| `--max-jobs-per-worker=<num>` | | `100` | Maximum jobs before recycling a worker process. |
| `--help` | `-h` | | Show help message and exit. |
| `--version` | `-V` | | Print version information and exit. |

---

### `list-breakpoints` Options

```text
Usage: code-tracer list-breakpoints [-hjpvV] [-i=<input>]
```

| Option | Flag | Default | Description |
| :--- | :--- | :--- | :--- |
| `--input=<file>` | `-i` | `stdin` | Input path to Java source file (defaults to `stdin` if omitted). |
| `--json` | `-j` | `false` | Output available breakpoints in structured JSON format. |
| `--pretty` | `-p` | `false` | Pretty-print JSON output with indentation. |
| `--verbose` | `-v` | `false` | Output messages about what the tracer is doing. |
| `--help` | `-h` | | Show help message and exit. |
| `--version` | `-V` | | Print version information and exit. |

---

### `show-licenses` Options

```text
Usage: code-tracer show-licenses [-hV]
```

| Option | Flag | Description |
| :--- | :--- | :--- |
| `--help` | `-h` | Show help message and exit. |
| `--version` | `-V` | Print version information and exit. |

---

## Output Formats & Schema

### 1. PythonTutor Format (`--format=pytutor`, default)

Generates Online Python Tutor JSON snapshots using nested tuple structures (`["INSTANCE", "ClassName", ["field", value]]` and `["REF", id]`):

#### Java Input

```java
public class Main {
  public static void main(String[] args) {
    Person alice = new Person("Alice", 42);
  }
}

record Person(String name, int age) { }
```

#### PythonTutor JSON Output

```json
{
  "code": "public class Main {\n ... }",
  "trace": [
    {
      "event": "step_line",
      "line": 4,
      "func_name": "main",
      "stack_to_render": [
        {
          "frame_id": 0,
          "func_name": "main:4",
          "ordered_varnames": ["alice"],
          "encoded_locals": { "alice": ["REF", 65] },
          "locals_attrs": { "alice": { "type": "Person", "final": false } }
        }
      ],
      "heap": {
        "65": ["INSTANCE", "Person", ["name", "Alice"], ["age", 42]]
      },
      "stdout": ""
    }
  ]
}
```

---

### 2. Modern Format (`--format=modern`)

Generates modern JSON snapshot objects with distinct type discrimination and metadata:

```json
{
  "code": "public class Main {\n ... }",
  "format": "modern",
  "stdin": "",
  "steps": [
    {
      "step": 1,
      "line": 4,
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 4,
          "isHighlighted": true,
          "locals": [
            {
              "name": "alice",
              "type": "Person",
              "value": { "ref": 65 },
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "65": {
          "id": 65,
          "type": "Person",
          "kind": "object",
          "fields": [
            {
              "name": "name",
              "type": "java.lang.String",
              "value": "Alice",
              "final": false
            },
            {
              "name": "age",
              "type": "int",
              "value": 42,
              "final": false
            }
          ]
        }
      },
      "stdout": "",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    }
  ]
}
```

---

### 3. JSON Envelope Wrapper (`--result-envelope`)

When enabled, wraps output in a versioned envelope detailing execution status, accounting metrics, and diagnostics:

```json
{
  "schemaVersion": 1,
  "format": "modern",
  "status": "completed",
  "stopReason": null,
  "phase": "trace",
  "complete": true,
  "trace": {
    "code": "public class Main {\n ... }",
    "trace": [ ... ]
  },
  "limits": {
    "timeoutMillis": 10000,
    "snapshots": 10000,
    "outputBytes": 1048576,
    "heapObjects": 10000,
    "elements": 100000,
    "traceBytes": 67108864,
    "sourceBytes": 1048576,
    "sourceFiles": 128
  },
  "counters": {
    "snapshotsCaptured": 1,
    "snapshotsRetained": 1,
    "retainedBytes": 1420,
    "droppedSnapshots": 0,
    "elapsedMillis": 34,
    "stdoutBytes": 0,
    "stderrBytes": 0
  },
  "diagnostics": [],
  "stdout": "",
  "stderr": ""
}
```

---

## Execution Limits & Inspection Policies

### Execution Limits

Limits can be tuned using CLI flags to prevent unbounded memory usage or infinite loops:

- `--timeout-ms=<ms>`: Wall-clock execution timeout in milliseconds.
- `--max-snapshots=<num>`: Caps the number of snapshots recorded.
- `--max-output-bytes=<bytes>`: Limits stdout and stderr capture buffers.
- `--max-heap-objects=<num>`: Limits the number of distinct reachable objects captured in heap snapshots.
- `--max-elements=<num>`: Limits inspected fields, array slots, and local variables.
- `--max-trace-bytes=<bytes>`: Caps memory consumed by formatted snapshot traces.
- `--max-source-bytes=<bytes>`: Maximum UTF-8 source code byte length before compilation.
- `--max-source-files=<num>`: Maximum streamed source files per submission.

### Inspection Policies

- `TRUSTED` *(default)*: Invokes helper methods on the target VM to inspect rich structures (e.g. collections, maps).
- `FIELDS`: Never invokes methods on the guest VM; inspects only primitive and object fields directly. Useful for running untrusted student code where method invocation might trigger side effects or infinite loops. Requires `--result-envelope`.

---

## Benchmarking

A benchmarking script is provided under [`benchmark/benchmark.py`](benchmark/benchmark.py) to measure tracing throughput and latency across multiple workloads:

```bash
# Run standard benchmark suite
python3 benchmark/benchmark.py
```

### Batch Mode Performance

The `batch-trace` command achieves significant throughput improvements over repeated CLI invocations by maintaining persistent worker JVMs and reusing JDI debugger connections across jobs:

```bash
# Tracing 24 multi-class example programs:
# Repeated one-shot CLI invocations: ~13.5s total (~560 ms/job)
# Persistent batch-trace invocation:   ~4.5s total (~190 ms/job)
# Speedup: ~3.0x faster
```

To run batch tracing:

```bash
# Stream NDJSON requests into batch-trace
cat jobs.ndjson | code-tracer batch-trace --workers=4
```

---

## Development & Pre-commit Quality Gate

### Git Hook Setup

To install local git hooks (including the pre-commit quality gate):

```bash
./scripts/install-git-hooks.sh
```

### Pre-commit Validation Checks

Before committing changes, run the pre-commit quality gate profile:

```bash
./mvnw -B -ntp -Ppre-commit-coverage clean test
```

This gate enforces:
1. **Compilation**: Clean compile with `--enable-preview` on JDK 21+.
2. **Checkstyle**: 100% adherence to project coding standards.
3. **Tests**: All JUnit unit tests must pass.
4. **Code Coverage**: 100% line coverage and 100% branch coverage across all production classes.

---

## License

This project is licensed under the terms of the Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International License ([CC-BY-NC-ND-4.0](LICENSE.md)).
