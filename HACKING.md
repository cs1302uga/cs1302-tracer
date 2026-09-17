# Project Architecture & Developer Guide

`cs1302-tracer` is a programmatic Java execution tracer built on top of the Java Debug Interface (JDI) and JavaParser. It compiles arbitrary guest Java code (including multi-file packages and streamed sources), executes it inside a separate guest JVM (without establishing host isolation), inspects execution states at method/line breakpoints, traverses the reachable object graph on the heap, and serializes the execution trace into JSON.

---

## 1. System Decomposition

The project is structured into modular components:

```mermaid
graph TD
    CLI["CLI Driver: App.java / picocli"] --> Comp["Compiler: CompilationHelper.java"]
    CLI --> Tracer["Trace Engine: DebugTraceHelper.java"]
    CLI --> SerPy["PyTutorSerializer.java"]
    CLI --> SerMod["ModernTraceSerializer.java"]

    Comp --> AST["JavaParser AST Analysis"]
    Comp --> Javac["Java Compiler API"]

    Tracer --> GuestJVM["Guest JVM Process"]
    GuestJVM -. "JDI Events" .-> Tracer
    Tracer --> Extractor["Value Extractor: TraceValue.java"]
    Extractor --> Snap["ExecutionSnapshot Record"]

    Snap --> SerPy --> OutPy["PythonTutor JSON"]
    Snap --> SerMod --> OutMod["Modern JSON"]
```

### Package Organization

| Package | Purpose |
| :--- | :--- |
| `cs1302.tracer` | Entry point, CLI command definitions (`App.java`), and compilation pipeline (`CompilationHelper.java`). |
| `cs1302.tracer.trace` | JDI execution engine (`DebugTraceHelper`), snapshot representation (`ExecutionSnapshot`), and heap value extractor (`TraceValue`). |
| `cs1302.tracer.model` | Common trace metadata and shared models (`TraceFormat`, `BreakpointEntry`). |
| `cs1302.tracer.model.pytutor` | PythonTutor compatibility models (`PyTutorTrace`, `TraceStep`, `RenderStackFrame`). |
| `cs1302.tracer.model.modern` | Modern clean JSON models (`Trace`, `Step`, `StackFrame`, `Variable`, `HeapObject`, `Reference`). |
| `cs1302.tracer.serialize` | Serializers converting snapshots into JSON (`PyTutorSerializer`, `ModernTraceSerializer`). |

---

## 2. Core Subsystems

### A. CLI Driver (`App.java`)

Built using [Picocli](https://picocli.info/), `App.java` handles argument parsing, subcommand routing, and pipeline orchestration.

- **Commands**:
  - `trace`: Main execution command. Options:
    - `-b, --breakpoint`: Specific line numbers to take snapshots at.
    - `-a, --all-breakpoints`: Traces every executable line chronologically.
    - `--accumulate-breakpoints`: Accumulates multiple hits on the same line into arrays.
    - `-f, --format`: Choose output format: `pytutor` (default) or `modern`.
    - `--remove-main-args`: Strips the `args` array parameter from `main(String[])` frame locals.
    - `--inline-strings`: Displays string values directly in variable slots rather than pointing to heap objects.
    - `--remove-method-this`: Omits the `this` reference from instance method stack frames.
  - `list-breakpoints`: Analyzes source files and lists valid line numbers where breakpoints can be set.
  - `show-licenses`: Dynamically reads and prints bundled third-party license notices (`META-INF/THIRD-PARTY.txt`).

---

### B. Compilation Engine (`CompilationHelper.java`)

`CompilationHelper` parses and compiles guest Java code into an isolated temporary directory before launching the tracer.

1. **Source Discovery & Parsing**:
   - Uses `com.github.javaparser` to inspect the Abstract Syntax Tree (AST).
   - Identifies package declarations, public classes/interfaces/enums/records, and locates the `main(String[] args)` method.
2. **Multi-File & Streaming Support**:
   - **File / Directory Inputs**: Resolves source roots based on package declarations (e.g. `cs1302/account/Driver.java` -> root directory).
   - **Standard Input Streaming**: Accepts multiple source files concatenated via standard input separated by comment headers:

     ```java
     // --- cs1302/math/Calculator.java ---
     package cs1302.math;
     public class Calculator { ... }

     // --- cs1302/math/Driver.java ---
     package cs1302.math;
     public class Driver { public static void main(String[] args) { ... } }
     ```

   - Automatically splits the stream, writes files into their corresponding package directory structure, and tracks file boundaries.
3. **Compilation**:
   - Compiles using `javax.tools.JavaCompiler` with debug flags (`-g`) enabled so full local variable tables and line number tables are emitted in `.class` files.
   - Wraps compiled classes in an autocloseable `CompilationResult` that cleans up temporary directories upon exit.

---

### C. Trace Engine (`DebugTraceHelper.java`)

`DebugTraceHelper` launches and debugs the guest JVM using the Java Debug Interface (`com.sun.jdi`).

```mermaid
sequenceDiagram
    participant Tracer as DebugTraceHelper
    participant JDI as JDI EventQueue
    participant Guest as Guest JVM

    Tracer->>Guest: Launch guest JVM with JDI connector
    Tracer->>JDI: Register MethodEntryRequest for Main.main()
    Guest->>JDI: VMStartEvent -> resume()
    Guest->>JDI: MethodEntryEvent (main)
    Tracer->>JDI: Register BreakpointRequest(s) / StepRequest
    loop Step / Breakpoint Event Loop
        Guest->>JDI: BreakpointEvent / StepEvent
        Tracer->>Tracer: Capture ExecutionSnapshot (Stack, Statics, Heap)
        Tracer->>Guest: resume()
    end
    Guest->>JDI: VMDeathEvent / VMDisconnectEvent
    Tracer->>Guest: Terminate & dispose VM
```

- **Tracing Modes**:
  - **Single Snapshot (Default)**: Takes a single snapshot at the final executable statement of `main`.
  - **Selected Breakpoints (`-b`)**: Places breakpoints at specified lines and records snapshots when hit.
  - **Chronological Stepping (`-a`)**: Automatically steps through every line across all files in the execution path.
- **Multi-File Context**:
  - Tags each snapshot, stack frame, and breakpoint with its relative source file path (e.g., `cs1302/account/Account.java`).
- **VM Lifecycle & I/O Isolation**:
  - Captures `stdout` and `stderr` streams separately in real time.
  - Destroys the guest process during cleanup and disposes the debugger connection. Bounded sessions also wait for process termination within a cleanup deadline.

---

### D. Value Extractor & Reachable Heap Traversal (`TraceValue.java`)

To capture heap state without depending on guest JVM memory addresses after execution ends, `TraceValue` inspects mirrored JDI values and converts them into host JVM objects:

1. **Primitive & Wrapper Values**: Extracted directly (e.g., `IntegerValue`, `BooleanValue`, `DoubleValue` handling `NaN` and infinities).
2. **String Values**: Captured as `TraceValue.String`.
3. **Reachable Object Graph Traversal**:
   - Starting from all static fields and active stack frame local variables, reachable objects are traversed recursively.
   - Circular references and pointer aliasing are tracked via `ObjectReference.uniqueID()`, ensuring shared objects reference the same heap entry.
4. **Specialized Heap Types**:
   - `ArrayReference` / `java.util.List` / `java.util.Collection` -> Array/List elements with reified runtime types.
   - `java.util.Map` -> Key-value pairs serialized as structured entries.
   - Lambdas & Method References -> Inspected via enclosing class and functional interface SAM name.
   - Boxed Primitives (`java.lang.Integer`, etc.) -> Extracted with underlying primitive payload.

---

### E. Serialization Subsystem (`cs1302.tracer.serialize`)

#### 1. PythonTutor Format (`PyTutorSerializer`)

Converts snapshots into the tuple/list JSON schema expected by Online Python Tutor:

- Heap objects encoded as tuple lists: `["INSTANCE", "ClassName", ["field", value], ...]` or `["LIST", elem1, elem2]`.
- Reified collection type labels in `heap_attrs` map.

#### 2. Modern Clean Format (`ModernTraceSerializer`)

Generates an explicit, developer-friendly JSON format:

- **Reference Semantics**: Pointer values are modeled explicitly as `{"ref": 42}`.
- **Heap Map**: Keyed by object ID (`"heap": { "42": { "kind": "object", "type": "Account", "fields": [...] } }`).
- **Flat Typed Variables**: `[{"name": "x", "type": "int", "value": 5, "final": false}]`.
- **Source File Metadata**: Steps and stack frames contain `"file": "cs1302/account/Driver.java"`.

---

## 3. Data Models (`cs1302.tracer.model`)

```text
cs1302.tracer.model
├── TraceFormat.java                     # Trace format enum (PYTUTOR, MODERN)
├── BreakpointEntry.java                 # Breakpoint line validity model
├── pytutor/                             # PythonTutor format models
│   ├── PyTutorTrace.java                # Root PythonTutor trace wrapper
│   ├── TraceStep.java                   # Individual PythonTutor execution step
│   └── RenderStackFrame.java            # Stack frame structure for PythonTutor
└── modern/                              # Modern format models
    ├── Trace.java                       # Root modern trace wrapper
    ├── Step.java                        # Individual modern execution step
    ├── StackFrame.java                  # Modern stack frame with thisObject ref
    ├── Variable.java                    # Typed variable representation
    ├── HeapObject.java                  # Heap object representation (object/array/string/box/lambda)
    └── Reference.java                   # Pointer reference object {"ref": id}
```

---

## 4. Development, Testing & Coverage

### Building the Project

```bash
# Compile and run unit tests with JaCoCo verification
mvn clean test

# Build fat JAR with all dependencies bundled
mvn package -DskipTests -Djacoco.skip=true
```

### JaCoCo Coverage Requirement

The JaCoCo check enforces **100% line coverage** and **100% branch coverage** for the configured `cs1302.tracer.model` and `cs1302.tracer.serialize` packages. It does not enforce that threshold across the tracing engine or all production packages. New lifecycle tests exercise real subprocesses with outer deadlines and cleanup; coverage percentage alone is not the release gate.

### Running Example Traces

The `examples/` directory contains 34 reference test cases (`example0` through `example33`) covering basic primitives, multi-file packages, loops, lambdas, instance methods, stdin streaming, pointer aliasing, varargs, unbuffered standard output, standard error capture, uncaught runtime exceptions, generic lists with autoboxing, active call stack frames, polymorphic generic container reification, multi-level class inheritance with dynamic dispatch, empty string and zero-length array instances, all numeric and non-numeric primitive wrapper class types, java.awt.Color objects with transparency and aliasing, guest standard input reading with java.util.Scanner, and Java preview features with java.lang.IO.

To regenerate all example outputs:

```bash
./examples/generate_all.sh
```

---

## 5. References & Further Reading

- [JDI - Java Debug Interface Specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jdi/index.html)
- [JavaParser Documentation](https://javaparser.org/)
- [Picocli User Manual](https://picocli.info/)
- [Online Python Tutor Trace Format](https://github.com/pgbovine/OnlinePythonTutor/blob/master/v3/docs/opt-trace-format.md)

## Bounded execution architecture

`cs1302.tracer.execution` contains the opt-in limits, result contract, inspection
policy, and `TraceSession`. A session binds to the caller thread for existing
static extraction helpers and owns the guest process, output-drainer lifecycle,
watchdog, stop reason, and retained snapshots. It is closed with try-with-resources;
the binding is not inherited by other threads. Cancellation can be requested from
another thread. The watchdog operates independently of JDI event handling.

Snapshot extraction accounts references/elements before retaining them and commits
only complete states. Size accounting uses a streaming counter rather than an
intermediate JSON string. Selected-breakpoint jobs can replace previous states
instead of retaining every hit. Budgeted output drainers cap retained bytes and
signal a stop while continuing to drain during teardown. The envelope is written
to stdout through Gson's writer API.

`BreakpointReader` uses ASM 9.10.1 to read SourceFile and line-number attributes
without loading or executing compiled classes. This keeps the project compatible
with the Java 21 runtime baseline while supporting the tested class-file versions.
See the [ASM release history](https://asm.ow2.io/versions.html). All compiled classes,
including unused nested classes and records, contribute to the source-line index.
Numeric CLI breakpoints retain their existing meaning across source files.

`FIELDS` inspection bypasses guest collection/accessor/wrapper/flush calls and
uses raw fields for ordinary objects. It intentionally differs from specialized
TRUSTED presentation and reports that policy in result diagnostics. This does not
constrain code executed by the guest itself.

The [result schema](docs/BOUNDED_TRACING.md) documents limits, partial outputs, and
failure semantics. The [runner contract](docs/RUNNER_CONTRACT.md) defines the
separate whole-job isolation boundary and authoritative external termination status.


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
