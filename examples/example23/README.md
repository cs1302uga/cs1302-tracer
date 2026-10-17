# Example 23: Uncaught Runtime Exceptions & Trace Termination

This example illustrates the runtime behavior of the tracer when an uncaught runtime exception (`ArithmeticException: / by zero`) occurs during execution using the modern trace format (`--format=modern -a`).

Add `--result-envelope` to receive `status: "failed"`, `stopReason: "guest_exception"`,
and CLI exit code 1 alongside the partial trace. Ordinary trace generation can
return exit code 0 even though the captured guest program failed.

## Concepts Illustrated

- **Uncaught Exception Behavior**: When an exception is thrown and not caught by an enclosing `try-catch` block, execution terminates abruptly at the throwing statement (`return a / b;`).
- **Preserved Call Stack at Crash**: The final captured execution step preserves the active `main` and `divide` frames at the invocation and failing division, including `a = 10` and `b = 0` in `divide`.
- **Standard Error Capture**: The standard error message and stack trace emitted by the JVM's uncaught exception handler are captured in the final execution step's `stderr` property.
- **Unreachable Code**: The `System.out.println("Result: " + result)` statement after the failing invocation is never reached or recorded in the trace.

## Files

- `cs1302/exceptions/uncaught/Driver.java`: Main program invoking a `divide(10, 0)` helper method that encounters an unhandled integer division by zero.
