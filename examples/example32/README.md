# Example 32: Guest Standard Input with java.util.Scanner

This example demonstrates providing standard input (`System.in`) to a traced Java program that reads tokens using `java.util.Scanner`.

## Concepts Illustrated

- **Guest Standard Input**: Supplying standard input via `--stdin` to populate `System.in`.
- **Scanner Parsing**: Reading multiple types (`String` via `next()`, `int` via `nextInt()`, and `double` via `nextDouble()`) from `System.in`.
- **Trace Input Capture**: Root `stdin` attribute populated in the execution trace output.
- **Unbuffered Standard Output**: Output produced alongside standard input reading.

## Files

- `cs1302/scanner/Driver.java`: Main driver demonstrating `java.util.Scanner` reading from `System.in`.
