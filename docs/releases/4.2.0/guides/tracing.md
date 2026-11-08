# Tracing programs

After [your first trace](../tutorials/first-trace.md) and
[reading its output](../tutorials/read-a-trace.md), choose the task you need below.
Work in the directory containing your downloaded `code-tracer.jar`, with a full
**JDK 21 or later** selected as described in [installation](installation.md).
Save the example files as UTF-8. No source checkout is needed.

Commands work in **Bash**, **PowerShell 7.4+**, and **Command Prompt** unless a
shell-specific alternative is shown. Tracing commands include the explicit `trace`
subcommand. `--input` (`-i`) takes a source file, not a directory; when omitted,
the tracer reads source from its standard input. That is separate from the input
read by the traced program.

| Task | Recipe |
| --- | --- |
| Capture the final state or a sequence of states | [Choose snapshots](#choose-snapshots) |
| Compare repeated hits at a loop line | [Trace a loop](../tutorials/trace-a-loop.md) |
| Give the program input | [Supply program input](#supply-program-input) |
| Submit a program with several source files | [Submit multiple files](#submit-multiple-files) |
| Use precompiled classes or a dependency JAR | [Add binary dependencies](#add-binary-dependencies) |
| Change output presentation or keep a partial result | [Choose presentation and budgets](#choose-presentation-and-budgets) |

## Choose snapshots

Save this as `SnapshotDemo.java`:

<!-- recipe-snapshots-source:start -->
```java
public class SnapshotDemo {
    public static void main(String[] args) {
        int value = 7;
        value++;
        System.out.println(value);
    }
}
```
<!-- recipe-snapshots-source:end -->

For the state at main exit, run:

<!-- recipe-final-command:start -->
```sh
java -jar code-tracer.jar trace -i SnapshotDemo.java --format modern --pretty > final-trace.json
```
<!-- recipe-final-command:end -->

For a sequence that includes the change from `7` to `8`, run:

<!-- recipe-snapshots-command:start -->
```sh
java -jar code-tracer.jar trace -i SnapshotDemo.java --all-breakpoints --format modern --pretty > snapshots.json
```
<!-- recipe-snapshots-command:end -->

Both runs capture final guest stdout `8` followed by a newline. Open `snapshots.json`
and follow `value` through the `main` frame's locals; a line stop can precede the
statement's effect.
The final-state command omits breakpoint options, so it selects main exit. Envelope
mode places that capture inside a sequence root.

To select particular stops, use `list-breakpoints` to discover executable lines;
blank lines and declarations need not have a location. The
[loop tutorial](../tutorials/trace-a-loop.md) compares the following choices:

| Selection | Retained snapshots |
| --- | --- |
| `--all-breakpoints` | Encountered stops in execution order. |
| `--breakpoints "12,Helper.java:5"` | Latest hit at each selected line, optionally qualified by file. |
| `--breakpoints=-1` | Main exit. |
| `--breakpoints 4 --accumulate-breakpoints` | Repeated hits at the selected line. Accumulation alone does not select loop lines. |
| `--breakpoints 4 --all-breakpoints` | Source-line stops restricted to the selection, retained chronologically. |

Chronological capture already retains repeated hits. Selected-breakpoint and
chronological output can have different roots; use the
[output reference](../reference/output.md#selected-breakpoints-and-envelopes) when
comparing their JSON.

## Supply program input

Save this as `Greeting.java`:

<!-- recipe-input-source:start -->
```java
import java.util.Scanner;

public class Greeting {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        String name = input.nextLine();
        System.out.println("Hello, " + name + "!");
    }
}
```
<!-- recipe-input-source:end -->

Save `input.txt` with this line and a newline at the end:

<!-- recipe-input-text:start -->
```text
Ada
```
<!-- recipe-input-text:end -->

<!-- recipe-input-command:start -->
```sh
java -jar code-tracer.jar trace -i Greeting.java --stdin-file input.txt --all-breakpoints --format modern --pretty > input-trace.json
```
<!-- recipe-input-command:end -->

The final captured `stdout` is `Hello, Ada!` followed by a newline. The source comes
from `Greeting.java`; the guest's input comes from `input.txt`. For a short literal
input, replace `--stdin-file input.txt` with `--stdin "Ada"`; do not supply both options.
Omitted guest input is an empty stream, so reads reach end-of-input instead of
prompting the tracer's terminal. Guest output is inside the JSON; tracer
diagnostics remain on terminal stderr.

## Submit multiple files

Save this complete bundle as `submission.txt`; the delimiter lines name the two
source files:

<!-- recipe-multifile-source:start -->
```java
// --- demo/Helper.java ---
package demo;
public class Helper {
    public static int answer() {
        return 42;
    }
}
// --- demo/Main.java ---
package demo;
public class Main {
    public static void main(String[] args) {
        System.out.println(Helper.answer());
    }
}
```
<!-- recipe-multifile-source:end -->

In Bash or Command Prompt, pass the bundle through the tracer's standard input:

<!-- recipe-multifile-command:start -->
```sh
java -jar code-tracer.jar trace --all-breakpoints --format modern --pretty < submission.txt > multi-trace.json
```
<!-- recipe-multifile-command:end -->

PowerShell does not support `<` input redirection. Use its UTF-8 text pipeline:

<!-- recipe-multifile-powershell-command:start -->
```powershell
Get-Content -Raw -Encoding utf8 submission.txt | java -jar code-tracer.jar trace --all-breakpoints --format modern --pretty > multi-trace.json
```
<!-- recipe-multifile-powershell-command:end -->

The final captured `stdout` is `42` followed by a newline. `sources` contains
`demo/Helper.java` and `demo/Main.java`; `entryFile` is `demo/Main.java`. The tracer
selects the first file with a recognized main-method candidate. File-qualified breakpoint line numbers
start at each file's first content line, excluding the delimiter.

Trusted file-based compilation can discover neighboring source dependencies from
the inferred source root. In `FIELDS` mode and multithread capture, include every
required source in the submitted bundle; neighboring discovery is disabled.
For Java code that assembles bundles programmatically, use the
[SourceBundle reference](../reference/java-api.md#bundle-source-files), including
its ordering, validation, file-capture, and link-following rules.

## Add binary dependencies

Use this recipe when the submitted program needs a compiled library. First save
`Helper.java` beside the tracer JAR:

<!-- recipe-dependency-source:start -->
```java
package teaching;

public class Helper {
    public static int answer() {
        return 42;
    }
}
```
<!-- recipe-dependency-source:end -->

Save the program to trace as `UseHelper.java`:

<!-- recipe-dependency-main:start -->
```java
import teaching.Helper;

public class UseHelper {
    public static void main(String[] args) {
        System.out.println(Helper.answer());
    }
}
```
<!-- recipe-dependency-main:end -->

Use a new `classes` directory. These JDK tools compile the helper and package its
classes; they do not run it:

<!-- recipe-dependency-build:start -->
```sh
mkdir classes
javac -d classes Helper.java
jar --create --file helper.jar -C classes .
```
<!-- recipe-dependency-build:end -->

<!-- recipe-dependency-command:start -->
```sh
java -jar code-tracer.jar trace -i UseHelper.java --class-path helper.jar --all-breakpoints --format modern --pretty > dependency-trace.json
```
<!-- recipe-dependency-command:end -->

The final captured `stdout` is `42` followed by a newline. The tracer compiles and
traces `UseHelper`; the compiled helper supplies its return value but receives no
source-line breakpoints. To use the compiled directory instead, replace `--class-path helper.jar`
with `--class-path classes`.

`--class-path`, `-classpath`, and `-cp` are repeatable tracer options after the
`trace` or `list-breakpoints` subcommand. The same entries serve compilation and
execution. Directory entries contain package-relative class files and resources;
adding a directory does not compile its Java sources or add JARs inside it.
Explicit binary dependencies also work with `FIELDS` inspection and multithread
tracing.

Each option accepts a platform-separated list (`:` on Linux/macOS, `;` on Windows);
repeated options append. Quote patterns so the tracer expands them. `lib/*` selects
immediate `.jar`/`.JAR` files, while `lib/**/*.jar` matches immediate and nested
lowercase `.jar` filenames. Pattern groups are sorted in place, and missing explicit
paths or patterns with no matches fail preparation. Relative paths use the tracer's
working directory. Local manifest dependencies are followed automatically;
manifest directory URLs must end with `/`, such as `classes/`.

Keep borrowed dependencies available and unchanged until tracing finishes, or use
`--copy-class-path` to capture them and available transitive manifest dependencies
before compilation. Glob discovery skips symbolic links by default, while directory
copying rejects links inside the tree. `--follow-class-path-links` enables following
for both operations, including targets outside the selected root, and rejects broken
links or cycles. Source limits do not bound binary dependency sizes. See the
[CLI reference](../reference/cli.md) and
[Java dependency API](../reference/java-api.md#binary-dependencies) for complete rules.

Guest compilation disables annotation processing. Supply any required generated
source files explicitly; annotations can still be used as ordinary dependency types.

## Choose presentation and budgets

Use `SnapshotDemo.java` from [Choose snapshots](#choose-snapshots) for this recipe:

<!-- recipe-presentation-command:start -->
```sh
java -jar code-tracer.jar trace -i SnapshotDemo.java --all-breakpoints --format modern --pretty --type-style simple --remove-main-args --include-primitive-bits > presentation-trace.json
```
<!-- recipe-presentation-command:end -->

The output still captures `value` changing from `7` to `8` and stdout `8` followed
by a newline. It omits the `main` frame's `args` local, uses simple type labels,
and includes binary metadata beside the primitive `value`. Compare it with `snapshots.json`:
presentation options change the representation, not the program's source.

`--format modern` produces typed JSON objects; `--format pytutor` is the default.
Ordinary modern JSON is always indented. `--pretty` indents Python Tutor output
and result envelopes without changing capture selection.
`--inline-strings`, `--remove-main-args`, `--remove-method-this`, and
`--type-style simple` adjust the presentation; all three boolean options default
to false, and the default type style is `fqn`.

Trusted inspection evaluates lazy enum hash codes by default. Use
`--no-eval-enum-hash` to suppress that evaluation, or `--eval-enum-hash` to enable
it explicitly. If both appear, the last setting wins. Field-only and multithread
inspection do not invoke guest methods regardless of this setting.

Add the optional `--include-primitive-bits` flag (no value required) to include
binary encodings alongside primitive literals. It defaults to false, supports
both formats and every capture mode, and does not require `--result-envelope`.
Use `--include-primitive-bits=true` or `--include-primitive-bits=false` to set it
explicitly; other explicit values are usage errors (exit 2).

The option does not change inspection policy, capture selection, or exit statuses.
`--multithread` still requires `--format modern`; invalid arguments still produce
CLI usage errors (exit 2). See the [output reference](../reference/output.md#optional-primitive-binary-metadata)
for array/field placement and payload-size implications, and
[encoding definitions and references](../reference/output.md#encoding-definitions-and-references)
for normative specifications and informative explanations of the encoding formats.

### Keep partial results when a budget stops tracing

Ordinary tracing uses finite default budgets. If a budget stops it, the command
exits with code `3` and writes no trace JSON. Use `--result-envelope` when you want
an explicit status and the completed snapshots captured before the stop.
**Envelope mode leaves omitted limits unlimited**, so choose every intended
budget explicitly instead of assuming the ordinary defaults carry over.

This demonstration uses `SnapshotDemo.java` from [Choose snapshots](#choose-snapshots).
It deliberately allows only one captured snapshot:

<!-- recipe-partial-command:start -->
```sh
java -jar code-tracer.jar trace -i SnapshotDemo.java --all-breakpoints --format modern --pretty --result-envelope --inspection FIELDS --timeout-ms 5000 --max-snapshots 1 --max-output-bytes 65536 --max-heap-objects 1000 --max-elements 10000 --max-trace-bytes 33554432 --max-source-bytes 262144 --max-source-files 32 --max-threads 64 --max-frames 4096 --max-snapshot-bytes 8388608 > partial-result.json
```
<!-- recipe-partial-command:end -->

Expect exit code `3`. Open `partial-result.json`: `status` is `stopped`, `complete`
is false, and `stopReason` is `snapshot_limit`. The completed snapshot is inside
`trace.steps`; it is useful partial evidence, not proof that the program finished.
For other failures, `trace` can be null when no usable payload is available.

These are illustrative budgets for a trusted example, not universal settings.
`FIELDS` avoids inspection-time guest method calls and requires an envelope when
selected explicitly. Neither inspection policy nor tracing budgets isolate guest
code, and a tracing timeout does not cover the whole job. See the
[trace model](../explanation/trace-model.md), [bounded result contract](../BOUNDED_TRACING.md),
and [runner contract](../RUNNER_CONTRACT.md) for those boundaries. The
[CLI reference](../reference/cli.md) defines the available flags and default values.
