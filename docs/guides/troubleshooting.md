# Troubleshooting

Start with the group matching what you observed. Run checks in the directory
containing `code-tracer.jar`, using the full **JDK** selected during
[installation](installation.md). Keep terminal stderr: it contains the tracer's
diagnostics, while the traced program's stdout and stderr are captured in its JSON.

## The tracer does not start

Try `java -jar code-tracer.jar --version` first. This reports the tracer and runtime
identity after environment validation. If validation itself fails, follow the
specific diagnostic rather than changing trace options.

| Symptom | Check or action | How to interpret it |
| --- | --- | --- |
| Launcher reports a missing or unreadable JAR | Keep `code-tracer.jar` beside the launcher, or check `CODE_TRACER_JAR_DIR`. Try direct `java -jar` invocation with the actual JAR path. | A nonempty invalid override does not fall back to the launcher's directory. |
| An unexpected Java version is selected | [Inspect the current selection](installation.md#inspect-your-current-java-selection), then [select an installed JDK for this session](installation.md#select-an-installed-jdk-for-this-terminal-session). Compare the explicit tool paths with the paths in the diagnostic. | A nonempty `JAVA_HOME` takes precedence; an invalid value does not trigger a search on `PATH`. |
| Compiler missing, tool versions disagree, or an early-access release is rejected | [Verify the selected tools](installation.md#verify-the-selected-tools). Use a full stable JDK 21 or later with executable `java` and `javac` whose complete release numbers match. | The environment check also runs for help, version, and licenses. Vendor wording and build metadata need not match; compiler/debugger modules are still required. |
| Direct JAR execution reports a different running JDK | Follow the [installation checkpoint](installation.md#verify-your-installation), which compares explicit-path and bare `java` invocation. | Changing the `java.home` property cannot switch the JVM that is already running. |
| A bare `java` or `javac` command still selects another tool after changing `PATH` | [Inspect command resolution](installation.md#inspect-your-current-java-selection) for an alias, function, or version-manager shim. Compare it with the selected JDK's explicit executable path, then correct the identified shell or shim configuration. | An alias or function can take precedence over `PATH`. The session recipe does not remove aliases or change persistent settings. |
| A version probe fails or times out | [Probe the selected executable directly](installation.md#verify-the-selected-tools). If it fails, repair that installation or its version-manager shim, or select another installed JDK. | Each probe has a five-second deadline and must exit successfully with a readable version. |
| **PowerShell** refuses a downloaded launcher | Use PowerShell 7.4+. Review the script and, if allowed by your organization, use `Unblock-File` on it. Alternatively use direct Java invocation or the `.cmd` launcher. | Script execution policy is separate from Java or source compilation. |
| No trace appears after starting the JAR | Include the `trace` subcommand; use `java -jar code-tracer.jar --help` to inspect commands. | Starting the root command does not submit a program for tracing. |

## Source, paths, or input are rejected

| Symptom | Check or action | How to interpret it |
| --- | --- | --- |
| A source or JAR path containing spaces is split | Quote the path as one argument, and quote classpath globs. **Command Prompt** also expands `%...%`; use its shell-specific quoting rules. | The tracer can only interpret the arguments the shell actually passes. |
| A command cannot find `examples/...` or `target/...` | Use the self-contained [first-trace example](../tutorials/first-trace.md) or [tracing recipes](tracing.md) beside the downloaded JAR. | Those other paths belong to a source checkout. |
| The source compiles with a newer Java release but fails here | Check the selected JDK and the compiler diagnostic. | JDK 21 cannot compile JDK 25 APIs; parser support also constrains accepted source syntax. |
| A dependency class cannot be found | Follow the [binary dependency recipe](tracing.md#add-binary-dependencies). Check paths relative to the tracer's working directory and confirm that the JAR or compiled directory exists. | Adding a directory does not compile its sources or automatically add JARs inside it. Missing paths and unmatched globs fail preparation. |
| The program cannot read its expected input | Supply `--stdin` or `--stdin-file`, not both; use the [input recipe](tracing.md#supply-program-input). | Source passed through the tracer's standard input is not guest input. Without guest input, reads see end-of-input rather than a terminal prompt. |
| PowerShell rejects `< submission.txt` | Use the documented [PowerShell pipeline](tracing.md#submit-multiple-files). | PowerShell does not support shell input redirection with `<`. |

## The trace looks different from what you expected

| Symptom | Check or action | How to interpret it |
| --- | --- | --- |
| No `7` appears in the terminal after the first trace | Open `trace.json` and inspect the final captured `stdout`. | Guest output is data inside the trace; terminal stderr holds tracer diagnostics. |
| A variable still has its old value at an assignment line | Inspect a later snapshot and compare its source location and locals. | A line stop can occur before the statement executes. See [Read a trace](../tutorials/read-a-trace.md). |
| A breakpoint never appears | Run `list-breakpoints` on the same source and verify the file and executable line. | Blank lines or declarations may not have a stop, and a selected executable line might never run. |
| Only one hit remains for a line in a loop | Compare the [loop capture choices](../tutorials/trace-a-loop.md). | A selected breakpoint retains its latest hit unless accumulation or chronological capture is requested. |
| Object IDs differ between runs | Compare local references with heap entries inside each result, not literal IDs across files. | IDs identify objects within one result; preserve their reference relationships when comparing traces. |
| Output appears repeatedly in snapshots | Read each snapshot as a cumulative prefix; do not concatenate every `stdout` value. | The final snapshot can receive trailing guest output. See the [trace model](../explanation/trace-model.md). |
| Collections have a different shape under `FIELDS` | Check the requested inspection policy and compare its documented representation. | Field-only inspection intentionally differs from trusted helper-based presentation. |

## A budget or failure stops the run

Inspect the exit status immediately after the command: use `echo $?` in **Bash**,
`$LASTEXITCODE` in PowerShell, or `echo %ERRORLEVEL%` in Command Prompt.

| Symptom | Check or action | How to interpret it |
| --- | --- | --- |
| Exit code `2` | Read the usage diagnostic and compare the command with the [CLI reference](../reference/cli.md). | An invalid argument can fail before a trace or result envelope is produced. |
| Exit code `3` and no JSON from ordinary tracing | Read the budget diagnostic. Tune that limit for your trusted example, or follow [the partial-result recipe](tracing.md#keep-partial-results-when-a-budget-stops-tracing). | Ordinary budget exhaustion writes no trace JSON. Redirected output can therefore be an empty file. |
| An envelope says `stopped` or `failed` | Read `complete`, `stopReason`, `phase`, and diagnostics before treating `trace` as a full execution. | Valid partial snapshots may remain; null means no usable trace payload was available. |
| The inspection option is rejected | Use `--result-envelope` with explicit `--inspection FIELDS`. | `--multithread --format modern` selects field-only inspection automatically; it still requires modern output. |
| An envelope is truncated or missing after the process is killed | Keep the process/runner termination evidence and treat incomplete JSON as an unusable result. | A kill need not produce an envelope. A hosted runner must report its own authoritative failure, not infer tracer success. |

Envelope mode leaves omitted budgets unlimited. Tracing budgets and inspection
policies do not provide whole-job isolation. The [bounded result contract](../BOUNDED_TRACING.md)
defines precise statuses, limits, and exit codes; the [runner contract](../RUNNER_CONTRACT.md)
defines requirements for hosted untrusted submissions.

## Report a reproducible problem

Include:

- The output of `java -jar code-tracer.jar --version`. If environment validation
  prevents it, include that diagnostic, the OS, and the selected `java`/`javac`
  versions and paths instead.
- Your shell, working directory, exact command, and exit status.
- A minimal trusted source example with every required source file, guest input,
  and any dependency versions or paths needed to reproduce it.
- Terminal stderr, the relevant trace or envelope excerpt, and whether its output
  file was complete, empty, or truncated.
- What you expected and what occurred. For reference mismatches, identify the
  variable and heap relationship rather than relying on an ID from another run.
