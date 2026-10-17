# Troubleshooting

| Symptom | What to check |
| --- | --- |
| Launcher reports missing or unreadable JAR | Keep `code-tracer.jar` beside the launcher, or check `CODE_TRACER_JAR_DIR`. A nonempty invalid override does not fall back. Try direct `java -jar` invocation with the actual JAR path. |
| Launcher selects an unexpected Java version | Check `JAVA_HOME` first, then `java --version` and `javac --version` on `PATH`. An invalid nonempty `JAVA_HOME` is an error, not a request to search `PATH`. |
| JDK preflight rejects a missing compiler, version mismatch, or early-access release | Select a full stable JDK 21 or later. Both tool release numbers must match completely; check the selected paths in stderr. The check also applies to help, version, and licenses. |
| Direct JAR invocation reports a different running JDK | Invoke the selected JDK's `bin/java -jar code-tracer.jar`, or correct `JAVA_HOME`/`PATH`; changing `java.home` cannot switch a running JVM. |
| A version probe fails or times out | Run the indicated tool manually and repair the JDK or version-manager shim. Each probe has a five-second deadline; it must exit successfully and produce a readable version. |
| **PowerShell** refuses the downloaded script | Use PowerShell 7.4+. Review the file and, if allowed by your organization, use `Unblock-File` on that script. Direct Java and the `.cmd` launcher are alternatives. |
| Source or JAR path with spaces is split | Quote the path as one argument. Quote classpath globs so the tracer receives the pattern. **Command Prompt** expands `%...%`; use its shell-specific quoting rules. |
| A command cannot find `examples/...` | Those paths belong to the source checkout. Download users can create the self-contained [first-trace example](../tutorials/first-trace.md). |
| No `7` appears in the terminal after the first trace | Guest output is captured inside `trace.json`; open its `stdout` fields. Tracer diagnostics use terminal stderr. |
| Compiler unavailable or debugger modules missing | Run with a full **JDK**, not a stripped runtime. Check `java -version` and your Java path. |
| Newer guest syntax or library fails compilation | Use the required JDK. Parser support also constrains accepted syntax. JDK 21 cannot compile JDK 25 APIs. |
| No trace when running the JAR | Include the `trace` subcommand. Root `--help` lists commands. |
| Breakpoint never appears | Use `list-breakpoints`; verify the source file and executable line. A selected line might not execute. |
| Exit code 3 and no JSON | An ordinary trace hit a budget. Tune the relevant limit or use `--result-envelope` with explicit budgets to receive partial results. |
| Inspection option rejected | Explicit `--inspection FIELDS` requires `--result-envelope`. `--multithread --format modern` selects field-only inspection automatically. |
| Guest cannot read expected input | Pass `--stdin` or `--stdin-file`. Source supplied through the tracer's stdin is not guest input. |
| Different object IDs across runs | IDs are references within a result, not stable identifiers. Preserve aliasing when comparing traces. |
| Different collection shape under `FIELDS` | Raw-field inspection intentionally differs from trusted helper-based presentation. |
| Truncated or missing envelope after a kill | The external runner must report termination; incomplete JSON is not a successful result. |

See [result statuses and limits](../BOUNDED_TRACING.md) for exit codes and diagnostics.
When reporting a bug, include the tracer version, JDK, command, a minimal trusted
source example, stderr, and the expected behavior.
