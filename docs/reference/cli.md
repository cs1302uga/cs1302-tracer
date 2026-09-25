# CLI reference

Generated from the executable by `python3 scripts/check_docs.py --update-cli`.
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
Usage: code-tracer trace [-ahpsvV] [--accumulate-breakpoints] [--eval-enum-hash] [--multithread]
                         [--no-eval-enum-hash] [--remove-main-args] [--remove-method-this]
                         [--result-envelope] [--unlimited] [-f=<format>] [-i=<input>]
                         [--inspection=<inspection>] [--max-elements=<elements>]
                         [--max-frames=<frames>] [--max-heap-objects=<heapObjects>]
                         [--max-output-bytes=<outputBytes>] [--max-snapshot-bytes=<snapshotBytes>]
                         [--max-snapshots=<snapshots>] [--max-source-bytes=<sourceBytes>]
                         [--max-source-files=<sourceFiles>] [--max-threads=<threads>]
                         [--max-trace-bytes=<traceBytes>] [--stdin=<stdin>]
                         [--stdin-file=<stdinFile>] [--timeout-ms=<timeoutMillis>]
                         [--type-style=<typeStyle>] [-b=<spec>[,<spec>...]]...
Generate an execution trace for a Java program.
  -a, --all-breakpoints      Include all encountered breakpoint instances in chronological order.
      --accumulate-breakpoints
                             Output an array of snapshots containing each reached breakpoint.
  -b, --breakpoints=<spec>[,<spec>...]
                             Breakpoints at which to take snapshots (e.g. '12', 'Main.java:12', or
                               comma-separated '12,Helper.java:5').
      --eval-enum-hash       Evaluate lazy enum hash codes when capturing snapshots.
  -f, --format=<format>      Output trace format: pytutor, modern (default: pytutor).
  -h, --help                 Show this help message and exit.
  -i, --input=<input>        Input path to Java source file (defaults to stdin if omitted).
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
Usage: code-tracer list-breakpoints [-hjpvV] [-i=<input>]
List the breakpoints available in the provided source file.
  -h, --help            Show this help message and exit.
  -i, --input=<input>   Input path to Java source file (defaults to stdin if omitted).
  -j, --json            Output available breakpoints in JSON format.
  -p, --pretty          Pretty-print JSON output.
  -v, --verbose         Output messages about what the tracer is doing.
  -V, --version         Print version information and exit.
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
