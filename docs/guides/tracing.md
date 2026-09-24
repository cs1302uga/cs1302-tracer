# Tracing programs

`trace` is an explicit subcommand. It accepts a Java source file with `--input`, or
reads source from standard input when the path is omitted. A directory is not an
input file. Trusted file-based compilation can discover neighboring dependencies
from the inferred source root.

## Choose snapshots

- With no breakpoint options, capture the end of `main`.
- `--all-breakpoints` captures encountered breakpoints chronologically.
- `--breakpoints '12,Helper.java:5'` selects lines, optionally qualified by file.
- The `-1` sentinel selects main exit: `--breakpoints=-1`.
- `--accumulate-breakpoints` retains multiple hits rather than the latest per selection.

Use `list-breakpoints` to discover valid lines; blank lines and declarations need
not correspond to executable locations.

## Supply program input

Source stdin and guest stdin are separate. Supply guest input with
`--stdin 'text'` or `--stdin-file input.txt`, but not both. Output from the guest is
captured in the JSON; the tracer writes diagnostics to stderr.

## Submit multiple files

Provide a self-contained UTF-8 stream with delimiter lines:

```java
// --- demo/Helper.java ---
package demo;
public class Helper { public static int answer() { return 42; } }
// --- demo/Main.java ---
package demo;
public class Main {
    public static void main(String[] args) { System.out.println(Helper.answer()); }
}
```

Save it as `submission.txt` and pass it to `trace` through stdin. In `FIELDS` mode,
include every required source in this bundle; neighboring file discovery is disabled.

## Choose presentation and budgets

`--format modern` produces typed JSON objects; `--format pytutor` is the default.
`--inline-strings`, `--remove-main-args`, `--remove-method-this`, and
`--type-style simple` adjust the presentation.

Ordinary tracing uses finite defaults. Envelope mode uses unlimited omitted limits,
so integrations should supply every intended budget explicitly. Read
[bounded tracing](../BOUNDED_TRACING.md) before choosing limits or inspection policy.
The [CLI reference](../reference/cli.md) is generated from the executable.
