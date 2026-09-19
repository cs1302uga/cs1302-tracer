# Tracer

Tracer is a static analysis and execution tracing tool for Java programs. It compiles guest Java source code and inspects JVM runtime memory state using the Java Debug Interface (JDI) to produce structured execution snapshots.

Tracer supports both [Online Python Tutor](https://pythontutor.com/)-compatible JSON output (for visualizers like [cs1302-code-visualizer](https://github.com/cs1302uga/cs1302-code-visualizer/)) and a **Modern Clean JSON format** designed for IDEs, web visualizers, and automated analysis.

---

## Features

- **Dual Output Formats**: Generate legacy PythonTutor traces or modern object-graph JSON traces with explicit reference pointers.
- **Reified Generics & Type Resolution**: Recovers erased generic type parameters for Java Collections and Maps (e.g., `ArrayList<String>`, `HashMap<Integer, Double>`) via static AST extraction and dynamic element sampling.
- **Chronological, Breakpoint & Exit Tracing**: Record step-by-step execution across all valid lines (`-a`), capture memory at specific line numbers (`-b 12`), target specific files in multi-file projects (`-b Main.java:12`), specify comma-separated breakpoint targets, or capture program termination (`-b -1`).
- **Multi-File & Streaming Support**: Trace multi-file Java packages from the filesystem or stream multiple sources via `stdin` using comment delimiters (`// --- path/to/File.java ---`).
- **Guest Standard Input Simulation & Highlight Offsets**: Provide guest input strings (`--stdin`) or files (`--stdin-file`) and track logical character consumption offsets (`stdinConsumed`, `stdinOffset`).
- **Bounded Resource Budgets & Result Envelopes**: Enforce configurable execution deadlines, snapshot caps, output byte caps, heap object limits, and trace byte limits with `--unlimited` overrides and an opt-in `--result-envelope`.
- **Type Qualification Styles**: Render type signatures using fully qualified names (`--type-style=fqn`, e.g., `java.lang.String`) or simplified short names (`--type-style=simple`, e.g., `String`).
- **Inspection Policies**: Choose between `TRUSTED` (rich helper inspection) and `FIELDS` (inspects fields without invoking guest methods for untrusted submissions).
- **Enum Constant & Hash Tracking**: Emits qualified enum constant labels with configurable lazy enum hash evaluation (`--eval-enum-hash` / `--no-eval-enum-hash`).
- **Lambda Reconstruction**: Extracts lambda expression bodies and creates concrete representations of functional interface implementations.
- **Immutability & Final Tracking**: Automatically tags and distinguishes `final` variables, record components, and object fields.
- **Breakpoint Introspection**: List all valid executable breakpoint lines per file in colorized console format or machine-readable JSON.

---

## Building and Installation

### Prerequisites

- **Java Development Kit (JDK)**: Version 21 or greater.
- **Apache Maven**: Version 3.8 or greater.

### Build Executable Fat JAR

```bash
# Compile and build the self-contained JAR (with all dependencies) and source bundle
mvn clean package
```

The resulting JAR will be located at:

```text
target/code-tracer-jar-with-dependencies.jar
```

---

## CLI Usage

Run the JAR directly with Java:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar [COMMAND] [OPTIONS]
```

### Subcommands

| Subcommand | Description |
| :--- | :--- |
| `trace` | Compiles and traces execution of a Java program. |
| `batch-trace` | Executes multiple trace jobs over an NDJSON stream reusing persistent guest JVM sessions. |
| `list-breakpoints` | Lists valid executable breakpoint lines for the source. |
| `show-licenses` | Displays open-source software license notices. |

---

### Common Workflows

#### 1. Trace End of Execution (Single Snapshot)

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java
```

Or explicitly target program exit using the `-1` breakpoint sentinel:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java -b -1
```

#### 2. Chronological Line-by-Line Execution Trace (`-a`)

Record all execution steps in modern format:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java -a -f modern
```

#### 3. Breakpoint-Specific Snapshots (`-b`)

Capture memory states before executing line 12:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java -b 12 -f modern
```

Target specific files in multi-file projects or specify comma-separated lists:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  -b "Main.java:12,Helper.java:24" -f modern
```

Capture each time a breakpoint line is hit (rather than only the final hit) using `--accumulate-breakpoints`:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  -b 12 --accumulate-breakpoints -f modern
```

#### 4. Guest Standard Input Simulation

Supply input strings or files to programs that read from `System.in`:

```bash
# Via literal string
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  --stdin "Alice 42\n" -a -f modern

# Via input file
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  --stdin-file ./input.txt -a -f modern
```

#### 5. Simplified Type Formatting (`--type-style simple`)

Render clean, unqualified type names in stack frames and heap objects:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  --type-style simple -a -f modern
```

#### 6. Multi-File Streaming via Standard Input

Concatenate multiple source files separated by comment headers and stream to tracer:

```bash
cat << 'EOF' | java -jar target/code-tracer-jar-with-dependencies.jar trace -a -f modern
// --- cs1302/model/Account.java ---
package cs1302.model;
public class Account {
    private int balance = 100;
    public int getBalance() { return balance; }
}

// --- cs1302/app/Driver.java ---
package cs1302.app;
import cs1302.model.Account;
public class Driver {
    public static void main(String[] args) {
        Account acc = new Account();
    }
}
EOF
```

#### 7. Bounded Execution & Result Envelope

Wrap trace results in a structured status envelope with custom timeouts and snapshot bounds:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java \
  --result-envelope --timeout-ms 5000 --max-snapshots 500 -a -f modern
```

Or disable default budget ceilings for large interactive runs:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java --unlimited -a
```

#### 7. Batch Mode Tracing (High-Throughput NDJSON)

Run multiple trace jobs over standard input or from a file without paying cold JVM startup or JDWP debugger socket handshake costs for each run:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar batch-trace -i ./jobs.ndjson
```

Or stream jobs directly via standard input and process concurrently with multiple worker sessions:

```bash
cat jobs.ndjson | java -jar target/code-tracer-jar-with-dependencies.jar batch-trace --workers 4
```

##### Input Format (NDJSON)

Each line is a JSON object with job options:

```json
{"id":"job-1","source":"public class Hello { public static void main(String[] args) { System.out.println(42); } }","format":"modern","allBreakpoints":true,"typeStyle":"simple"}
```

##### Output Format (NDJSON)

Each output line contains the job correlation `id` and versioned `TraceResult`:

```json
{"id":"job-1","result":{"schemaVersion":1,"format":"modern","status":"completed","complete":true,"trace":{"code":"...","steps":[...]}}}
```

##### Performance Comparison

Generating execution traces across all 34 reference test cases in `examples/` demonstrates substantial throughput improvements by avoiding repeated cold JVM startups and JDWP debugger socket handshakes:

| Execution Mode | Workers | Total Time (34 examples) | Latency / Trace | Throughput | Speedup |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Non-Batch** (one-shot `trace` CLI) | 1 | **28.71 s** | 844.4 ms | 1.2 traces/s | **1.00x** (baseline) |
| **Batch** (`batch-trace -w 1`) | 1 | **4.64 s** | 136.5 ms | 7.3 traces/s | **6.18x** |
| **Batch** (`batch-trace -w 2`) | 2 | **2.84 s** | 83.4 ms | 12.0 traces/s | **10.12x** |
| **Batch** (`batch-trace -w 4`) | 4 | **2.09 s** | 61.4 ms | 16.3 traces/s | **13.76x** |

- **Session Reuse (6.18x faster)**: Comparing single-worker batch mode (`-w 1`) directly against traditional one-shot execution isolates the exact penalty of JVM startup and JDWP socket handshakes, cutting trace latency from ~844 ms down to ~136 ms.
- **Concurrent Worker Scaling (Up to 13.76x faster)**: Distributing jobs across concurrent worker sessions (`--workers 4`) finishes all 34 test cases in ~2.09 seconds at 16.3 traces/sec.

#### 8. Inspect Valid Breakpoints

Show colorized executable lines in the terminal:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar list-breakpoints -i ./Main.java
```

Or retrieve as structured JSON:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar list-breakpoints -i ./Main.java -j -p
```

#### 9. View Dependency Licenses

```bash
java -jar target/code-tracer-jar-with-dependencies.jar show-licenses
```

---

## Command Options Reference

### Root Options

```text
Usage: code-tracer [-hV] [COMMAND]
```

| Option | Flag | Description |
| :--- | :--- | :--- |
| `--help` | `-h` | Show help message and exit. |
| `--version` | `-V` | Print version information and exit. |

---

### `trace` Options

```text
Usage: code-tracer trace [-ahpsvV] [--accumulate-breakpoints]
                         [--eval-enum-hash] [--no-eval-enum-hash]
                         [--remove-main-args] [--remove-method-this]
                         [--result-envelope] [--unlimited] [-f=<format>]
                         [-i=<input>] [--inspection=<inspection>]
                         [--max-elements=<elements>]
                         [--max-heap-objects=<heapObjects>]
                         [--max-output-bytes=<outputBytes>]
                         [--max-snapshots=<snapshots>]
                         [--max-source-bytes=<sourceBytes>]
                         [--max-source-files=<sourceFiles>]
                         [--max-trace-bytes=<traceBytes>] [--stdin=<stdin>]
                         [--stdin-file=<stdinFile>]
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
      "heap_attrs": {
        "65": { "type": ["java.lang.String", "int"], "final": [true, true] }
      }
    }
  ]
}
```

### 2. Modern Clean Format (`--format=modern` or `-f modern`)

Produces an explicit, typed object graph with dictionary-backed heaps and pointer references:

```json
{
  "code": "public class Main {\n ... }",
  "steps": [
    {
      "line": 4,
      "event": "step_line",
      "file": "Main.java",
      "stack": [
        {
          "methodName": "main",
          "line": 4,
          "file": "Main.java",
          "variables": [
            {
              "name": "alice",
              "type": "Person",
              "value": { "ref": 65 },
              "final": false
            }
          ]
        }
      ],
      "heap": {
        "65": {
          "kind": "object",
          "type": "Person",
          "fields": [
            { 
              "name": "name", 
              "type": "java.lang.String", 
              "value": "Alice", 
              "final": true 
            },
            {  
              "name": "age", 
              "type": "int", 
              "value": 42, 
              "final": true 
            }
          ]
        }
      },
      "stdout": "",
      "stderr": ""
    }
  ]
}
```

### 3. Result Envelope (`--result-envelope`)

For programmatic consumers, grading harnesses, and hosted runners, `--result-envelope` wraps the execution outcome in a versioned document:

```json
{
  "schemaVersion": 1,
  "format": "modern",
  "status": "completed",
  "stopReason": null,
  "phase": "trace",
  "complete": true,
  "trace": { ... },
  "limits": {
    "timeoutMillis": 5000,
    "snapshots": 500,
    "outputBytes": 65536,
    "heapObjects": 1000,
    "elements": 10000,
    "traceBytes": 33554432,
    "sourceBytes": 262144,
    "sourceFiles": 32
  },
  "diagnostics": []
}
```

#### Exit Codes

| Exit Code | Meaning |
| :--- | :--- |
| `0` | Successful trace completed and emitted. |
| `1` | General error (e.g., compilation failure, unhandled guest exception). |
| `2` | Invalid command-line arguments or contradictory options. |
| `3` | Resource budget limit exceeded during bounded trace execution. |

---

## Advanced Execution & Type Capabilities

### Reified Generics for Collections & Maps

In standard Java execution, generic type parameters are erased at runtime due to JVM type erasure. Tracer reconstructs and preserves generic type information across traces:

1. **Static AST Analysis**: Extracts declared type arguments (e.g. `List<Person>`, `Map<String, Integer>`) from local variable, parameter, and field declarations.
2. **Dynamic Runtime Heap Sampling**: For raw collections or generic instances where declarations are absent, Tracer samples runtime element types to reconstruct type signatures (e.g., `ArrayList<java.lang.String>`, `HashMap<java.lang.Integer, java.lang.Double>`).

### Input Highlighting & Stdin Tracking

When a guest program reads from standard input (via `Scanner`, `BufferedReader`, or `java.lang.IO.readln`), Tracer tracks the input logically consumed:

- `stdinConsumed`: The substring of input logically read by completed reader operations.
- `stdinOffset`: The 0-based Java UTF-16 character index into the supplied input string.

Reader lookahead buffers are excluded so that only data actually consumed by the program advances the offset. Raw UTF-8 bytes advance offsets once a full character sequence has completed.

### Bounded Jobs & Inspection Policies

By default, ordinary `trace` runs enforce finite default budgets (10-second deadline, 10,000 snapshots, 1 MiB per output stream, 10,000 heap objects, 100,000 elements, 64 MiB trace data, 1 MiB source, and 128 source files).

- **Budget Stops**: If a cap is exceeded, Tracer exits with code `3`. Under ordinary mode, stderr reports the stop reason. Under `--result-envelope`, a structured JSON envelope is produced with `status: "stopped"` and the specific `stopReason`.
- **`TRUSTED` Policy**: Default policy. Inspects collections, maps, and wrappers using runtime helper methods (e.g., `toArray`, `entrySet`).
- **`FIELDS` Policy**: Avoids invoking any guest methods during inspection. Inspects object states strictly via field reflection. Requires `--result-envelope` and self-contained source bundles.

For details on limits, accounting models, and sandboxing requirements, see [docs/BOUNDED_TRACING.md](docs/BOUNDED_TRACING.md) and [docs/RUNNER_CONTRACT.md](docs/RUNNER_CONTRACT.md).

---

## Development & Testing

- **Run Unit Tests & JaCoCo Coverage**:

  ```bash
  mvn clean test
  ```

  *(Enforces 100% line and branch coverage across model and serialize packages.)*

- **Run Checkstyle Verification**:

  ```bash
  mvn checkstyle:check
  ```

- **Run Reference Verification**:

  ```bash
  mvn package
  python3 examples/verify.py
  ```

- **Run an Individual Example**:

  ```bash
  ./examples/test.sh examples/Simple.java -a -f modern
  ```

For architecture diagrams, subsystem design, value extraction mechanics, and contribution guidelines, see [HACKING.md](HACKING.md).
