# Example 7: Loops & Breakpoint Accumulation

This example demonstrates repeated loop iteration and accumulating breakpoint snapshots across loop steps.

## Concepts Illustrated

- **Loop Control**: Tracing index variable increments and condition checks.
- **Breakpoint Accumulation**: Combining `--breakpoints` with `--accumulate-breakpoints` retains repeated hits at the selected loop lines. Use `--all-breakpoints` to capture all executable lines chronologically; accumulation alone does not select loop lines.

## Files

- `Driver.java`: Sums numbers in a `for` loop.
