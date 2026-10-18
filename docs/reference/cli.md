# CLI reference

Generated from the executable by `./scripts/uv run python scripts/check_docs.py --update-cli`.
Do not edit help blocks by hand. `trace` is an explicit subcommand.

## Root command

```text
Usage: code-tracer [-hV] [COMMAND]
Trace Java program execution and inspect memory states.
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  trace             Generate an execution trace for a Java program.
  batch-trace       Execute independent trace jobs concurrently over an NDJSON stream.
  list-breakpoints  List the breakpoints available in the provided source file.
  show-licenses     Show the licenses for projects used in this program and then exit.
```

## trace

```text
Usage: code-tracer trace [-ahpsvV] [--accumulate-breakpoints] [--copy-class-path]
                         [--eval-enum-hash] [--follow-class-path-links] [--include-primitive-bits]
                         [--multithread] [--no-eval-enum-hash] [--remove-main-args]
                         [--remove-method-this] [--result-envelope] [--unlimited] [-f=<format>]
                         [-i=<input>] [--inspection=<inspection>] [--max-elements=<elements>]
                         [--max-frames=<frames>] [--max-heap-objects=<heapObjects>]
                         [--max-output-bytes=<outputBytes>] [--max-snapshot-bytes=<snapshotBytes>]
                         [--max-snapshots=<snapshots>] [--max-source-bytes=<sourceBytes>]
                         [--max-source-files=<sourceFiles>] [--max-threads=<threads>]
                         [--max-trace-bytes=<traceBytes>] [--stdin=<stdin>]
                         [--stdin-file=<stdinFile>] [--timeout-ms=<timeoutMillis>]
                         [--type-style=<typeStyle>] [-b=<spec>[,<spec>...]]... [-cp=<paths>]...
Generate an execution trace for a Java program.
  -a, --all-breakpoints      Include all encountered breakpoint instances in chronological order.
      --accumulate-breakpoints
                             Output an array of snapshots containing each reached breakpoint.
  -b, --breakpoints=<spec>[,<spec>...]
                             Breakpoints at which to take snapshots (e.g. '12', 'Main.java:12', or
                               comma-separated '12,Helper.java:5').
      --copy-class-path      Copy selected dependencies and their local manifest dependencies
                               before compilation (default: false). Otherwise keep borrowed inputs
                               unchanged until the job completes. Applies to --class-path entries.
      -cp, -classpath, --class-path=<paths>
                             Binary dependency path or platform-separated path list (: on Unix, ;
                               on Windows). Repeat to append in order. Empty components mean the
                               working directory; default: no entries. Quote patterns: lib/*
                               selects immediate JARs; lib/**/*.jar also searches descendants.
                               Windows drive-relative globs retain their drive prefix. Matches are
                               sorted; missing entries and patterns without matches fail
                               preparation.
      --eval-enum-hash       Evaluate lazy enum hash codes when capturing snapshots.
  -f, --format=<format>      Output trace format: pytutor, modern (default: pytutor).
      --follow-class-path-links
                             Follow symbolic links during class-path glob discovery and directory
                               copying, including outside targets (default: false). Broken links
                               and cycles fail. By default glob discovery skips links and copying
                               rejects encountered links.
  -h, --help                 Show this help message and exit.
  -i, --input=<input>        Input path to Java source file (defaults to stdin if omitted).
      --include-primitive-bits
                             Include fixed-width primitive binary metadata alongside values
                               (default: false). Optional flag; no value required. Works with both
                               formats and all capture modes; does not require an envelope.
                               Increases serialized payload size.
      --inspection=<inspection>
                             Inspection policy: TRUSTED, FIELDS; FIELDS invokes no methods.
      --max-elements=<elements>
                             Inspected elements per snapshot; 0 is unlimited.
      --max-frames=<frames>  Total frames per snapshot; 0 is unlimited.
      --max-heap-objects=<heapObjects>
                             Objects per snapshot; 0 is unlimited.
      --max-output-bytes=<outputBytes>
                             Guest bytes per stream; 0 is unlimited.
      --max-snapshot-bytes=<snapshotBytes>
                             Bytes per snapshot; 0 is unlimited.
      --max-snapshots=<snapshots>
                             Maximum captured snapshots; 0 is unlimited.
      --max-source-bytes=<sourceBytes>
                             UTF-8 source bytes; 0 is unlimited.
      --max-source-files=<sourceFiles>
                             Streamed source files; 0 is unlimited.
      --max-threads=<threads>
                             Live application threads; 0 is unlimited.
      --max-trace-bytes=<traceBytes>
                             Accounted snapshot bytes; 0 is unlimited.
      --multithread          Capture application threads in modern JSON.
      --no-eval-enum-hash    Do not evaluate lazy enum hash codes when capturing snapshots.
  -p, --pretty               Pretty-print JSON output.
      --remove-main-args     Don't include the main method's args parameter in the output.
      --remove-method-this   Don't include the value of this for methods in the output.
      --result-envelope      Emit versioned job status and trace JSON.
  -s, --inline-strings       If provided, strings are inlined into fields.
      --stdin=<stdin>        Input string provided to the traced program via standard input.
      --stdin-file=<stdinFile>
                             Path to file whose content is provided to the traced program via
                               standard input.
      --timeout-ms=<timeoutMillis>
                             Tracing deadline in milliseconds; 0 is unlimited.
      --type-style=<typeStyle>
                             Type qualification style: fqn, simple (default: fqn).
      --unlimited            Disable default budgets; explicit limits still apply.
  -v, --verbose              Output messages about what the tracer is doing.
  -V, --version              Print version information and exit.
```

## batch-trace

```text
Usage: code-tracer batch-trace [-hV] [--completion-order] [-i=<input>]
                               [--max-in-flight=<maxInFlight>]
                               [--max-jobs-per-worker=<maxJobsPerWorker>] [-w=<workers>]
Execute independent trace jobs concurrently over an NDJSON stream.
      --completion-order    Emit results as jobs finish; default is submission order.
  -h, --help                Show this help message and exit.
  -i, --input=<input>       Input path to NDJSON file (defaults to stdin if omitted).
      --max-in-flight=<maxInFlight>
                            Maximum admitted jobs and buffered results (default: 16).
      --max-jobs-per-worker=<maxJobsPerWorker>
                            Maximum jobs before recycling a worker process (default: 100).
  -V, --version             Print version information and exit.
  -w, --workers=<workers>   Number of concurrent worker sessions (default: 1).
```

## list-breakpoints

```text
Usage: code-tracer list-breakpoints [-hjpvV] [--copy-class-path] [--follow-class-path-links]
                                    [-i=<input>] [-cp=<paths>]...
List the breakpoints available in the provided source file.
      --copy-class-path   Copy selected dependencies and their local manifest dependencies before
                            compilation (default: false). Otherwise keep borrowed inputs unchanged
                            until the job completes. Applies to --class-path entries.
      -cp, -classpath, --class-path=<paths>
                          Binary dependency path or platform-separated path list (: on Unix, ; on
                            Windows). Repeat to append in order. Empty components mean the working
                            directory; default: no entries. Quote patterns: lib/* selects immediate
                            JARs; lib/**/*.jar also searches descendants. Windows drive-relative
                            globs retain their drive prefix. Matches are sorted; missing entries
                            and patterns without matches fail preparation.
      --follow-class-path-links
                          Follow symbolic links during class-path glob discovery and directory
                            copying, including outside targets (default: false). Broken links and
                            cycles fail. By default glob discovery skips links and copying rejects
                            encountered links.
  -h, --help              Show this help message and exit.
  -i, --input=<input>     Input path to Java source file (defaults to stdin if omitted).
  -j, --json              Output available breakpoints in JSON format.
  -p, --pretty            Pretty-print JSON output.
  -v, --verbose           Output messages about what the tracer is doing.
  -V, --version           Print version information and exit.
```

## show-licenses

```text
Usage: code-tracer show-licenses [-hV]
Show the licenses for projects used in this program and then exit.
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
```

## Effective budgets

Ordinary tracing uses finite defaults; envelope and batch requests use unlimited
omitted limits. Zero disables a limit. See [bounded tracing](../BOUNDED_TRACING.md)
for default values, inspection policies, exit codes, and partial results.

<!-- cli-reference:manual -->

## Trace defaults at a glance

These defaults apply to the ordinary `trace` command. Explicit options can change
both the retained snapshots and the JSON root shape.

| Setting | Default | Change it with |
| --- | --- | --- |
| Source | Read source text from the tracer's standard input. | `--input Main.java` (alias `-i`). |
| Guest input | Empty input. | `--stdin TEXT` or `--stdin-file FILE`; choose one. |
| Capture | Final main-exit state. | `--all-breakpoints` (`-a`) for chronological capture, or `--breakpoints` (`-b`) for selected locations. |
| Repeated selected hits | Retain the latest hit at each location. | `--accumulate-breakpoints` to retain hits grouped by location. |
| JSON format | `pytutor`. | `--format modern` (`-f modern`). |
| Type names | Fully qualified (`fqn`). | `--type-style simple`. |
| Layout | Compact Python Tutor JSON; ordinary modern JSON is always indented. | `--pretty` (`-p`) indents Python Tutor output and result envelopes. |
| Binary dependencies | None; the host `CLASSPATH` is not imported. | Repeatable `--class-path`, `-classpath`, or `-cp`. |
| Budgets | Finite instructor defaults. | Explicit `--max-*` options or `--timeout-ms`; `--result-envelope` switches omitted limits to unlimited. |

`--type-style` also controls the return and parameter types in
[reconstructed lambda signatures](output.md#lambda-implementation-types), including
generic arguments and arrays. It preserves the lambda body's text in both formats.

See [result contracts](output.md#result-contracts) for the exact JSON root shapes,
[bounded tracing](../BOUNDED_TRACING.md) for numeric defaults, and
[tracing recipes](../guides/tracing.md) for complete commands and source files.

## Exit statuses

For a single `trace` invocation, check both the process status and, when requested,
the result envelope. Capture stderr separately from JSON stdout.

| Status | Meaning | Next action |
| --- | --- | --- |
| `0` | Trace generation completed. An ordinary trace can still record an exception thrown by the guest program. | Inspect the trace's events and guest output; exit status alone does not establish that the submitted program behaved correctly. |
| `1` | Environment preflight or trace generation failed. | Read stderr. With an envelope, also inspect `status`, `phase`, and `diagnostics` if an envelope was produced. |
| `2` | Invalid command arguments or configuration. | Read stderr and compare the invocation with `trace --help`. |
| `3` | A tracing limit caused a controlled stop. | With `--result-envelope`, inspect the stopped result and any retained prefix. Ordinary mode emits a diagnostic without partial JSON. |

Preflight runs before command work and can prevent an envelope from being produced.
The [bounded result contract](../BOUNDED_TRACING.md) defines completion, failure,
stop reasons, and unavailable versus empty payloads. Batch clients should use the
[batch protocol's per-job statuses](batch.md), not this single-command table.

## JDK environment preflight

Every CLI invocation first validates its Java environment, including `--help`,
`--version`, license display, and all subcommands. A nonempty `JAVA_HOME` is
authoritative: both `bin/java` and `bin/javac` must exist and be executable
(`java.exe` and `javac.exe` on Windows). An invalid selection never falls back to
`PATH`. Without `JAVA_HOME`, both executables must be found on `PATH`.

Both tools' version probes must succeed within five seconds each. Their complete
release numbers must match (for example, `25.0.4.1` differs from `25.0.4`); vendor
wording and build metadata are ignored. Only stable releases of JDK 21 or later
are accepted. Early-access and other prerelease versions, unreadable version
output, failed probes, and timeouts are errors. CI tests JDK 21 and 25; accepting a
newer stable JDK does not mean that every newer release has been tested.

The running JDK must also be the selected JDK, accounting for symlinks and equivalent
paths. This matters for direct `java -jar` invocation: setting `JAVA_HOME` does not
change an already-running JVM. Launch with that JDK's `bin/java` or correct the
environment. The tracer does not rewrite `java.home` or relaunch itself. Supported
Java library calls through `Tracer` and `BatchTracer` do not run this CLI preflight.

A preflight failure prints a diagnostic to stderr, leaves stdout empty, and exits
with status **1** before command work begins. Diagnostics identify the selected
tool paths or versions when available and suggest correcting `JAVA_HOME` or `PATH`.
The executable JAR includes a small Java-8-compatible bootstrap so Java 8–20 can
report the unsupported runtime before loading the Java-21-compiled application.

## Version and runtime details

`--version` (alias `-V`) takes no value and prints a five-line report to standard
output, then exits with status 0 after the environment preflight succeeds. It works on the root command and on `trace`,
`batch-trace`, `list-breakpoints`, and `show-licenses`, without executing their work
or requiring their normal input arguments. The installed launchers and direct
`java -jar code-tracer.jar --version` invocation share the same implementation.

Version output uses the same automatic ANSI color detection and controls as help.
The entire first line is bold, including the version and commit. The remaining
four lines are unstyled; no foreground colors or dim styling are applied. Plain output retains the same five lines and text.
Picocli uses console detection rather than a stdout-only terminal check: redirecting
stdin can also disable automatic color. Redirected or piped output is normally
plain; an explicit color override can still enable ANSI escape sequences.
Set `NO_COLOR` to disable automatic color, or use `-Dpicocli.ansi=false` before
`-jar` to force plain output. `-Dpicocli.ansi=true` forces bold styling, including when
output is redirected. These JVM properties take precedence over automatic detection.

Example output (values depend on the build and the JVM running it):

```text
code-tracer 4.2.0-SNAPSHOT (caf1355923fcea4e8587a06c7bf1d008935b66e9)
macOS 26.7.1 aarch64
openjdk 25.0.4.1 2026-08-18
OpenJDK Runtime Environment Homebrew (build 25.0.4.1)
OpenJDK 64-Bit Server VM Homebrew (build 25.0.4.1, mixed mode, sharing)
```

The first line contains the packaged application version and full source commit.
A `-dirty` suffix on the commit means tracked files had uncommitted changes when
built; untracked files do not affect it. `(unknown)` means source provenance was
unavailable. The remaining lines describe the host OS and the running JVM, using
runtime properties. Rendering the report starts no additional process; the startup
preflight separately probes the selected tools. Vendor wording can differ from the
JDK's native version banner; unavailable fields display `unknown`.

This replaces the previous single-line version value. Scripts that consumed the
whole output as a version must instead read the second whitespace-separated field
of the first line. Missing packaged version metadata uses `development`.
Source-archive rebuilds retain the archive's originating provenance; edits after
extraction are not detected unless the sources are placed in a Git checkout.


## Binary dependencies

`trace` and `list-breakpoints` accept the optional aliases `--class-path`,
`-classpath`, and `-cp`. Each occurrence requires one argument: a path or a native
path list separated by `:` on Linux/macOS and `;` on Windows. Repeated and mixed
aliases append entries in command-line order. For example, on Linux/macOS:

```sh
java -jar target/code-tracer.jar trace \
  --input Main.java --class-path build/classes -cp "lib/**/*.jar:vendor/helpers.jar"
```

The dependency files in this example must already exist. Quote arguments to retain
spaces and prevent shell wildcard expansion. Quotes do not escape native path-list
separators: use a literal `Path` through the [Java interface](java-api.md#binary-dependencies)
or a literal [batch entry](batch.md#binary-dependencies) for filenames containing
the platform separator. Empty list components select the process working directory;
an entirely empty argument does the same. Omitting all class-path options supplies
no dependencies and does not import the host `CLASSPATH` environment variable.

Entries may be binary/resource directories, JAR files, or patterns. `lib/*` follows
Java's immediate-JAR shorthand, selecting `.jar` and `.JAR` files directly in `lib`.
`lib/**/*.jar` selects lowercase `.jar` files at zero or more directory levels.
Other patterns use the portable glob grammar documented for
[`SourceBundle`](java-api.md#bundle-source-files), including `?`, character classes,
and brace alternatives. The literal native prefix supplies the search root; the
remaining glob uses `/` separators, with native Windows separators accepted by
the CLI adapter. Relative roots and paths use native filesystem resolution in the
tracer process, independently of the input source file's directory.

Windows drive-relative patterns keep the drive prefix in their search root:
`C:library-*.jar` uses root `C:` and glob `library-*.jar`. The same rule applies
before `?`, character classes, and brace alternatives, including when a literal
filename stem precedes the first glob character. These paths remain drive-relative;
use `C:/library/*.jar` for an absolute drive-rooted pattern.

Each pattern's matches are sorted by case-sensitive, locale-independent relative
path at that pattern's position. Overlapping selections retain the first occurrence
of the same normalized absolute path. The compiled job output precedes dependencies;
platform classes retain their normal precedence. A directory does not implicitly
include JARs stored inside it or request compilation of its `.java` files. Source
discovery keeps its existing caller-specific policy. Local manifest dependencies
are followed transitively; manifest entries are URLs rather than glob patterns.
Manifest directory URLs require a trailing `/`; directories named without it and
archives named with it are ignored. Literal command-line directory paths do not
require a trailing separator.

Repeated options, recursive globs, deterministic match ordering, and strict
missing-entry/zero-match errors are tracer extensions to
[Java's class-path syntax](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#standard-options-for-java).
Missing explicit paths, unreadable or invalid archives, and patterns with no
matches fail preparation with path/pattern diagnostics. Missing manifest targets
follow Java's ignore behavior. `trace` reports invalid option/pattern syntax with
exit code 2; preparation failures use exit code 1, with compile-phase diagnostics
inside `--result-envelope` output. `list-breakpoints` reports failures on stderr
with exit code 1. It compiles with dependencies but never launches a guest or
includes dependency line tables.

`--copy-class-path` is an optional flag requiring no value; it defaults to false.
Supplying the flag enables copying; `--copy-class-path=true` and
`--copy-class-path=false` set it explicitly.
By default dependencies are borrowed and must remain available and unchanged
until the command finishes. Copying captures dependencies and their available
local manifest closure before compilation, preserves JAR bytes and signatures,
and uses the copies for compilation, type analysis, and execution. Owned copies
are removed after the job. Copying is not an atomic snapshot of changing inputs.

`--follow-class-path-links` is also optional, requires no value, and defaults to
false. Supplying it enables following; `--follow-class-path-links=true` and
`--follow-class-path-links=false` set it explicitly. By default glob discovery
skips discovered symbolic links and copying directory contents rejects them.
Enabling it follows targets, including those
outside the search root; broken links and directory cycles fail. Explicit paths
and search roots retain normal filesystem resolution. Both flags apply to class-path
selections in ordinary/envelope tracing and breakpoint discovery; neither requires
an envelope. `batch-trace` takes dependency choices per request instead of these flags.

Guest annotation processing is disabled with `-proc:none`; supply required
generated declarations as source. Dependencies acquire no source-line breakpoints.
See [Java dependency semantics](java-api.md#binary-dependencies) for ownership,
resource/service lookup, loader qualifications, and binary-resource budget scope.
