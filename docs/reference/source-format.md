# Delimited source bundle

A delimited source bundle is one text value that carries several Java source
files. Every entry point accepts it wherever it accepts source text: the CLI
`trace` and `list-breakpoints` commands (through `--input` or standard input), the
batch request's `source` field, and the Java API's `TraceRequest` source and
`Tracer.discoverBreakpoints`. Java callers can build one with
[`SourceBundle`](java-api.md#bundle-source-files). Source text without any
delimiter line is a single file.

## Delimiter lines

A delimiter line names the file whose content follows it:

```java
// --- demo/Helper.java ---
```

A line is a delimiter when all of these hold:

| Part | Rule |
| --- | --- |
| Start | The line begins with `//`; no leading whitespace is allowed. |
| Opening marker | After optional spaces or tabs, three or more `-` or `=` characters. |
| Path | After optional spaces or tabs, a path ending in `.java`. Surrounding spaces and tabs are trimmed. |
| Closing marker | Three or more `-` or `=` characters, followed only by optional spaces or tabs. |

Markers may mix `-` and `=`, so `// === Main.java ===` is also a delimiter. Line
ends are `\n`, `\r`, `\r\n`, U+0085, U+2028, and U+2029. A `//` comment line that
does not match every rule is ordinary source text.

## File contents

Each file's content runs from the end of its delimiter line to the start of the
next delimiter line, or to the end of the bundle. One `\n` or `\r\n` immediately
after the delimiter is removed, so line 1 of each file is the first line after its
delimiter. Breakpoint locations and trace line numbers use these file-relative
numbers.

Text before the first delimiter line is ignored. Each file is parsed separately
and must be valid Java on its own; a parse failure names the file.

## Paths

A path containing `/` or `\` is used as written, with `\` treated as `/`. A bare
file name, such as `Main.java`, is placed under its package directory: a file
declaring `package demo;` becomes `demo/Main.java`. A bare file name in the
default package stays at the root.

Every resulting path must be relative, contain no `:`, stay inside the bundle root
after normalization, and end in `.java`. Two files cannot resolve to the same
path. Any violation rejects the whole bundle with
`Invalid or duplicate source path` before any file is written.

The resolved path decides where the file is compiled. When a bundle has two or
more files, `list-breakpoints` reports each file by its resolved path; a one-file
bundle is listed as undelimited text (see
[breakpoint listing output](cli.md#breakpoint-listing-output)).
`Tracer.discoverBreakpoints` and traces instead name each file by its debug source
path: the declared package directory plus the file name. A file delimited as
`src/demo/Main.java` that declares `package demo;` appears as `demo/Main.java` in
the result's `sources` map, `entryFile`, and step and frame `file` fields. A
file-qualified breakpoint names it by that debug source path, `demo/Main.java:LINE`,
or by a shorter suffix such as `Main.java:LINE`, which can match more than one
submitted file. A selection that names the bundle path, `src/demo/Main.java:LINE`,
matches nothing: the trace has no snapshot for it and reports no error. See
[shared source metadata](output.md#shared-source-metadata).

## Single files

Source with no delimiter line is one file. Its name is derived from its public
top-level type, under its package directory, so it must declare exactly one
public top-level type. See [program requirements](../guides/tracing.md#program-requirements).

## Entry file and file counts

The entry file is the first file, in bundle order, that contains a recognized
main-method candidate; see [program requirements](../guides/tracing.md#program-requirements)
for what counts as a candidate. Helper files need no main method.

The `--max-source-files` and `sourceFiles` budgets count delimiter-defined files;
source without delimiters counts as one file. The source-byte budget counts the
whole bundle. See [limits](../BOUNDED_TRACING.md#limits).

## Example

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

This bundle contains `demo/Helper.java` and `demo/Main.java`; `demo/Main.java`
is the entry file. The [multiple-file recipe](../guides/tracing.md#submit-multiple-files)
traces it.
