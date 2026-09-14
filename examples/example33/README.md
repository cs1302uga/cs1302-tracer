# Example 33: Guest Standard Input with java.lang.IO Preview Features

This example demonstrates standard input handling combined with Java preview features (`java.lang.IO`).

## Concepts Illustrated

- **Java Preview Feature Support**: Automatic detection of preview feature usage (`IO.readln` and `IO.println`) without requiring manual compiler or JVM flags.
- **java.lang.IO Standard Input**: Reading lines from `System.in` using `IO.readln(prompt)`.
- **Trace Input Capture**: Root `stdin` attribute populated in the execution trace output.
- **Standard I/O Flow**: Combining prompt emissions to stdout and input absorption from stdin.

## Files

- `cs1302/io/Driver.java`: Main driver demonstrating `java.lang.IO.readln` with `--stdin`.
