# Troubleshooting

| Symptom | What to check |
| --- | --- |
| Compiler unavailable or debugger modules missing | Run with a full JDK, not a stripped runtime. Check `java -version` and your Java path. |
| Newer guest syntax or library fails compilation | Use the required JDK. Parser support also constrains accepted syntax. JDK 21 cannot compile JDK 25 APIs. |
| No trace when running the JAR | Include the `trace` subcommand. Root `--help` lists commands. |
| Breakpoint never appears | Use `list-breakpoints`; verify the source file and executable line. A selected line might not execute. |
| Exit code 3 and no JSON | An ordinary trace hit a budget. Tune the relevant limit or use `--result-envelope` with explicit budgets to receive partial results. |
| Inspection option rejected | `FIELDS` inspection requires `--result-envelope`. |
| Guest cannot read expected input | Pass `--stdin` or `--stdin-file`. Source supplied through the tracer's stdin is not guest input. |
| Different object IDs across runs | IDs are references within a result, not stable identifiers. Preserve aliasing when comparing traces. |
| Different collection shape under `FIELDS` | Raw-field inspection intentionally differs from trusted helper-based presentation. |
| Truncated or missing envelope after a kill | The external runner must report termination; incomplete JSON is not a successful result. |

See [result statuses and limits](../BOUNDED_TRACING.md) for exit codes and diagnostics.
When reporting a bug, include the tracer version, JDK, command, a minimal trusted
source example, stderr, and the expected behavior.
