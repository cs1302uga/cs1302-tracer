# Tracer

Tracer is a static analysis and execution tracing tool for Java programs. It compiles guest Java source code and inspects JVM runtime memory state using the Java Debug Interface (JDI) to produce structured execution snapshots.

Tracer supports both [Online Python Tutor](https://pythontutor.com/)-compatible JSON output (for visualizers like [cs1302-code-visualizer](https://github.com/cs1302uga/cs1302-code-visualizer/)) and a **Modern Clean JSON format** designed for IDEs, web visualizers, and automated analysis.

---

## Features

- **Dual Output Formats**: Generate legacy PythonTutor traces or modern object-graph JSON traces with explicit reference pointers.
- **Reified Generics & Type Resolution**: Recovers erased generic type parameters for Java Collections and Maps (e.g., `ArrayList<String>`, `HashMap<Integer, Double>`) via static AST extraction and dynamic element sampling.
- **Chronological & Breakpoint Tracing**: Record step-by-step execution across all valid lines (`-a`) or capture memory at specific line numbers (`-b`).
- **Multi-File & Streaming Support**: Trace multi-file Java packages from the filesystem or stream multiple sources via `stdin` using comment delimiters (`// --- path/to/File.java ---`).
- **Lambda Reconstruction**: Extracts lambda expression bodies and creates concrete representations of functional interface implementations.
- **Immutability & Final Tracking**: Automatically tags and distinguishes `final` variables, record components, and object fields.
- **Breakpoint Introspection**: List all valid executable breakpoint lines per file in colorized console format or machine-readable JSON.

---

## Advanced Type Capabilities

### Reified Generics for Collections & Maps

In standard Java execution, generic type parameters are erased at runtime due to JVM type erasure. Tracer reconstructs and preserves generic type information across traces:

1. **Static AST Analysis**: Extracts declared type arguments (e.g. `List<Person>`, `Map<String, Integer>`) from local variable, parameter, and field declarations.
2. **Dynamic Runtime Heap Sampling**: For raw collections or generic instances where declarations are absent, Tracer samples the runtime types of items in the collection to reconstruct type signatures (e.g., `ArrayList<java.lang.String>`, `HashMap<java.lang.Integer, java.lang.Double>`).

#### Example

```java
List<String> names = new ArrayList<>();
names.add("Ada");
```

In the output trace metadata (`heap_attrs`), Tracer emits the full reified generic type:

```json
{
  "heap_attrs": {
    "42": {
      "type": "java.util.ArrayList<java.lang.String>"
    }
  }
}
```

---

## Output Formats

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
java -jar target/code-tracer-jar-with-dependencies.jar [subcommand] [options]
```

### Subcommands

| Subcommand | Description |
| :--- | :--- |
| `trace` | Compiles and traces execution of a Java program. |
| `list-breakpoints` | Lists valid executable breakpoint lines for the source. |
| `show-licenses` | Displays open-source software license notices. |

---

### Common Workflows

#### 1. Trace End of Execution (Single Snapshot)

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java
```

#### 2. Chronological Line-by-Line Execution Trace (`-a`)

Record all execution steps in modern format:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java -a -f modern
```

#### 3. Breakpoint-Specific Snapshots (`-b`)

Capture memory states only before executing lines 12 and 24:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar trace -i ./Main.java -b 12 -b 24 -f modern
```

#### 4. Multi-File Streaming via Standard Input

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

#### 5. Inspect Valid Breakpoints

Show colorized executable lines in the terminal:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar list-breakpoints -i ./Main.java
```

Or retrieve as structured JSON:

```bash
java -jar target/code-tracer-jar-with-dependencies.jar list-breakpoints -i ./Main.java -j
```

---

### Command Options Reference

#### `trace` Options

```text
Usage: code-tracer trace [-ahpsvV] [--accumulate-breakpoints] [--remove-main-args]
                         [--remove-method-this] [-f=<format>] [-i=<input>]
                         [-b=<breakpoints>]...
```

| Option | Flag | Description |
| :--- | :--- | :--- |
| `--input=<file>` | `-i` | Input path to Java source file (defaults to `stdin`). |
| `--format=<format>` | `-f` | Output format: `pytutor` (default) or `modern`. |
| `--pretty` | `-p` | Pretty-print JSON output with indentation. |
| `--all-breakpoints` | `-a` | Record all encountered breakpoints in chronological order. |
| `--breakpoints=<lines>` | `-b` | Line numbers at which to capture snapshots. |
| `--accumulate-breakpoints` | | Output an array of snapshots for each breakpoint hit instead of only the last. |
| `--inline-strings` | `-s` | Inlines string values into fields/variables rather than allocating heap objects. |
| `--remove-main-args` | | Omit the `args` parameter in the `main` method stack frame. |
| `--remove-method-this` | | Omit the `this` reference variable from method stack frames. |
| `--verbose` | `-v` | Output debug diagnostic logs. |
| `--help` | `-h` | Display help message. |

---

## Development & Testing

- **Run Unit Tests & JaCoCo Coverage**:

  ```bash
  mvn clean test
  ```

  *(Enforces 100% line and branch coverage across production classes)*

- **Run Reference Examples**:

  ```bash
  ./examples/test.sh
  ```

For detailed architecture diagrams, design decisions, value extraction mechanics, and contribution guidelines, see [HACKING.md](HACKING.md).
