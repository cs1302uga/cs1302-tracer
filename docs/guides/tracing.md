# Tracing programs

`trace` is an explicit subcommand. It accepts a Java source file with `--input`, or
reads source from standard input when the path is omitted. A directory is not an
input file. Trusted file-based compilation can discover neighboring dependencies
from the inferred source root.

## Choose snapshots

- With no breakpoint options, ordinary tracing returns the final state at main
  exit; envelope mode returns that capture inside a sequence root.
- `--all-breakpoints` captures encountered breakpoints chronologically. Combine it
  with `--breakpoints` to restrict source-line stops while retaining their order.
- `--breakpoints '12,Helper.java:5'` selects lines, optionally qualified by file.
- The `-1` sentinel selects main exit: `--breakpoints=-1`.
- `--accumulate-breakpoints` retains multiple hits rather than the latest per
  selected location. Use it with `--breakpoints`; by itself it does not select
  loop lines. Chronological mode already retains repeated hits.

Use `list-breakpoints` to discover valid lines; blank lines and declarations need
not correspond to executable locations.

## Supply program input

Source stdin and guest stdin are separate. Supply guest input with
`--stdin 'text'` or `--stdin-file input.txt`, but not both. Output from the guest is
captured in the JSON; the tracer writes diagnostics to stderr. Omitted guest
input is an empty stream, so reads reach end-of-input instead of prompting the
tracer's terminal.

## Submit multiple files

Provide a self-contained UTF-8 stream with delimiter lines:

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

Save it as `submission.txt` and pass it to `trace` through stdin:

```sh
java -jar target/code-tracer.jar trace -a -f modern < submission.txt
```

In `FIELDS` mode and multithread capture, include every required source in this
bundle; neighboring file discovery is disabled. File-qualified breakpoint line
numbers count from the beginning of each file's content, excluding its delimiter.

Java integrations can construct the same input with `SourceBundle.builder()`
(since 4.1.0). Use `addSource(logicalPath, text)` for explicit names or
`addInferredSource(text)` to derive a name from a public or sole top-level type.
`addFile(logicalPath, path)` and `addInferredFile(path)` capture UTF-8 files when
added. For a directory tree, use `addFiles(root, "demo/**/*.java")` for paths
relative to `root`, or `addInferredFiles(root, "demo/**/*.java")` for names inferred
from each selected file's declarations. The portable, case-sensitive pattern
matches both `demo/Main.java` and files in deeper directories. Each call appends
matches in lexical root-relative path order and captures all text before returning;
a failed selection, read, or validation leaves the builder unchanged. Symbolic
links within the root are skipped by default. The overload with `followLinks=true`
follows them, including targets outside the root; encountered broken links and
cycles fail the whole call. The selected root itself may be a symbolic link.
Build once, then pass `bundle.source()` to `TraceRequest.builder`, obtain a
fresh stream with `bundle.openStream()`, or write the UTF-8 bundle with
`bundle.writeUtf8(output)`. The latter leaves flushing and closing to the caller.
See [bundle source files](../reference/java-api.md#bundle-source-files) for complete examples.

The builder preserves insertion order and source text, including line endings and
BOMs. It inserts an LF only when needed before the next file's delimiter, and leaves
the final file unchanged. It rejects duplicate logical paths and source lines that
match the delimiter grammar, including matches inside text blocks or comments.
Glob selection does not discover dependencies from imports. Capturing files does
not compile them or establish that all dependencies are present.
The tracer's existing entry-point selection remains in effect: the first file with
a recognized main-method candidate is selected.

## Add binary dependencies

Supply directories containing package-relative class files/resources and JARs with
repeatable `--class-path`, `-classpath`, or `-cp` options. These are tracer options
after the `trace` or `list-breakpoints` subcommand:

```sh
java -jar target/code-tracer.jar trace --input Main.java \
  --class-path build/classes -cp 'lib/**/*.jar' --format modern
```

The same entries are available to compilation and execution. Dependencies do not
receive source-line breakpoints. A directory is a binary/resource root; adding it
does not request compilation of its Java sources. Explicit binary dependencies
also work with `FIELDS` inspection and multithread tracing.

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

`--format modern` produces typed JSON objects; `--format pytutor` is the default.
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
explicitly; other explicit values are usage errors (exit 2). For example:

```sh
java -jar target/code-tracer.jar trace -i examples/primitive-bits/Main.java -a -f modern --include-primitive-bits
```

The option does not change inspection policy, capture selection, or exit statuses.
`--multithread` still requires `--format modern`; invalid arguments still produce
CLI usage errors (exit 2). See the [output reference](../reference/output.md#optional-primitive-binary-metadata)
for array/field placement and payload-size implications, and
[encoding definitions and references](../reference/output.md#encoding-definitions-and-references)
for normative specifications and informative explanations of the encoding formats.

Ordinary tracing uses finite defaults. Envelope mode uses unlimited omitted limits,
so integrations should supply every intended budget explicitly. Read
[bounded tracing](../BOUNDED_TRACING.md) before choosing limits or inspection policy.
The [CLI reference](../reference/cli.md) is generated from the executable.
