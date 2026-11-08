# Trace a loop

Trace a print statement that executes three times, then compare three ways to
retain those visits. Start after [Read a trace](read-a-trace.md), in the directory
containing `code-tracer.jar`. The commands work in **Bash**, **PowerShell 7.4+**, and
**Command Prompt** with the full **JDK 21 or later** used for the first lesson.

## Create the loop

Save these exact seven lines as `Loop.java` beside the JAR. Keep the line breaks:
the print statement must be on line 4.

<!-- loop-source:start -->
```java
public class Loop {
    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            System.out.println(i);
        }
    }
}
```
<!-- loop-source:end -->

Before tracing, predict the values of `i` when execution reaches line 4. The
program prints `0`, `1`, and `2`, each on its own line.

## Find the executable location

Discover the available source locations and save the result:

<!-- loop-locations:start -->
```sh
java -jar code-tracer.jar list-breakpoints -i Loop.java --json --pretty > locations.json
```
<!-- loop-locations:end -->

Open `locations.json` and find the entry with `lineNumber` equal to `4`.
Its `lineContent` is the print statement and `validBreakpoint` is `true`.
Discovery identifies executable locations; it does not execute the loop.

## Retain the latest visit

Select line 4 and save an ordinary trace:

<!-- loop-latest:start -->
```sh
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --format modern --pretty > latest.json
```
<!-- loop-latest:end -->

Open `latest.json`. Its root has a `breakpoints` map, whose `"4"` entry is one
snapshot. Find the local named `i` in its `main` frame: its value is `2`.
The earlier visits with `i` equal to `0` and `1` were replaced by the latest visit.

The retained state's line is still `4`, but its `stdout` contains the complete
output: `0`, `1`, and `2`, each followed by a newline. This selected-breakpoint
mode attaches final output to the last retained hit; the stream does not mean
the final print had already executed when the local values were captured.

## Retain every visit at that location

Add `--accumulate-breakpoints`:

<!-- loop-accumulated:start -->
```sh
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --accumulate-breakpoints --format modern --pretty > accumulated.json
```
<!-- loop-accumulated:end -->

In `accumulated.json`, `breakpoints["4"]` is now an array of three snapshots.
Read the `i` local in each `main` frame. The values are `0`, `1`, and `2` in that
order, matching your prediction.

Check the output as well. The first snapshot's `stdout` is empty, the next
contains the first printed line, and the last contains all three printed lines.
Like the previous mode, this mode puts final output on the last retained hit.
The grouping answers “what states were retained at this location?”

## Keep a chronological sequence

Use `--all-breakpoints` with the same selection:

<!-- loop-chronological:start -->
```sh
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --all-breakpoints --format modern --pretty > chronological.json
```
<!-- loop-chronological:end -->

This result has a `steps` array instead of a `breakpoints` map. Find the steps for
`Loop.java` line 4 and inspect `i` in their `main` frames. You should again find
`0`, `1`, and `2` in execution order. Their output prefixes show the state before
each print:

| Local `i` at line 4 | Lines already present in `stdout` |
| --- | --- |
| `0` | None |
| `1` | `0` |
| `2` | `0`, then `1` |

The trace also contains a final main-exit capture at line 6. That capture has the
complete printed output, and `i` is no longer in scope. Selecting line 4 restricts
source-line breakpoint stops; it does not remove the final main-exit capture.
Find these states by source location and local values, not fixed array positions.

## Compare what you saved

All three commands run the same loop. Their retention and output shapes differ:

| File | Where to look | What you retained |
| --- | --- | --- |
| `latest.json` | `breakpoints["4"]` | One state, with `i` equal to `2`. |
| `accumulated.json` | `breakpoints["4"]` | Three states grouped at line 4, with `i` equal to `0`, `1`, and `2`. |
| `chronological.json` | `steps` | The three selected visits in execution order, followed by the final main-exit capture. |

Each of the three trace files should identify `Loop.java` as its `entryFile`, and
each successful command exits with status `0`. If a command fails or a file is
empty, use [troubleshooting](../guides/troubleshooting.md) before comparing results.

Use [tracing programs](../guides/tracing.md) to choose options for another program,
or read [How a trace represents execution](../explanation/trace-model.md) to connect
capture, retention, and output. The
[result-contract reference](../reference/output.md#result-contracts) defines the
other result shapes and links to completion and budget behavior.
