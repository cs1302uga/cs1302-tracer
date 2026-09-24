# Example 21: Unbuffered Standard Output & Progress Prompts

This example demonstrates step-by-step capture of unbuffered console output using `System.out.print` (without trailing newlines) alongside iterative loop execution (`trace -a`).

## Concepts Illustrated

- **Unbuffered Stdout Capture**: Trusted inspection can flush guest output so prompts can appear in snapshots before a newline. `FIELDS` inspection does not invoke guest flush methods; it captures only bytes already emitted to OS pipes.
- **Progress Tracking**: Progress indicator tokens (`"."`) accumulating step-by-step across loop iterations.
- **Chronological Trace**: Full step sequence preserving intermediate console output states.

## Files

- `cs1302/io/stdout/Driver.java`: Emits an unbuffered status prompt, iterates through a loop writing dots, and outputs a final completion message.
