# Example 33: Guest Standard Input with java.lang.IO

This example uses `java.lang.IO` and requires a JDK providing that API; use JDK 25
for this example. It is not compatible with the tracer's minimum JDK 21 runtime.

## Concepts Illustrated

- **Compiler Configuration**: The tracer detects `java.lang.IO` use and supplies its compiler/JVM configuration automatically. API availability still depends on the active JDK.
- **java.lang.IO Standard Input**: Reading lines from `System.in` using `IO.readln(prompt)`.
- **Trace Input Capture**: Root `stdin` attribute populated in the execution trace output.
- **Standard I/O Flow**: Combining prompt emissions to stdout and input absorption from stdin.

## Files

- `cs1302/io/Driver.java`: Main driver demonstrating `java.lang.IO.readln` with `--stdin`.
