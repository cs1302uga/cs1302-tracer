# Read a trace

Start with `trace.json` from [your first trace](first-trace.md). Open it alongside
`Main.java`. A snapshot records the debugger's captured state at a stop; it is not
a record of every bytecode instruction or every memory operation.

## Follow a local variable into the heap

The root `sources` map contains the submitted source, and `entryFile` chooses the
entry source. Each chronological element of `steps` has a source `file` and a
1-based `line`. A line stop can occur before that line's statement executes: look
at a later snapshot to see the assignment's effect.

Find a snapshot in which `main` has both `box` and `answer` locals:

| Part of the snapshot | What it tells you |
| --- | --- |
| `callStack` | The captured application frames and their local variables. |
| Local `answer` | An `int` value of `7`, copied from the field. |
| Local `box` | A reference of the form `{"ref": id}`. |
| `heap` entry with that ID | The `Box` object, whose `value` field is `7`. |
| `stdout` | Guest output observed up to this capture. |

The local `box` refers to the object; it does not contain a second copy of the
object. Two references with the same ID in one result refer to the same object.
IDs may change on another run, so compare relationships rather than literal IDs.
The complete machine-checked examples and field conventions live in the
[JSON output reference](../reference/output.md).

## Observe output over time

Before the print executes, `stdout` is empty. The final snapshot contains `7\n`.
Output is cumulative, so concatenating every snapshot's stdout would duplicate
text. `stderr` similarly records guest stderr. The tracer process writes its own
diagnostics to the terminal's stderr, outside the JSON trace.

## Observe a repeated breakpoint

Save this second program as `Loop.java` beside the JAR:

```java
public class Loop {
    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            System.out.println(i);
        }
    }
}
```

Discover executable lines, then compare captures at line 4:

```sh
java -jar code-tracer.jar list-breakpoints -i Loop.java --json --pretty
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --format modern --pretty
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --accumulate-breakpoints --format modern --pretty
java -jar code-tracer.jar trace -i Loop.java --breakpoints 4 --all-breakpoints --format modern --pretty
```

The first trace retains the latest hit for the selected location. Accumulation
retains repeated hits; chronological capture retains them in execution order.
Combining `--all-breakpoints` with a selection restricts source-line stops to that
selection. Ordinary selected-breakpoint output and chronological output have
different root shapes; do not assume every trace has a `steps` array.

## Choose the result contract

| Interface | Result | Omitted budgets |
| --- | --- | --- |
| Ordinary `trace` | Trace payload; root shape depends on capture selection and format | Finite interactive defaults |
| `trace --result-envelope` | Status, completion, diagnostics, streams, limits, and optional trace payload | Unlimited |
| `batch-trace` | One response per job, with its `id` and result envelope | Unlimited |
| Java convenience `new TraceRequest(source)` | `TraceOutcome`, with envelope and payload accessors | Finite instructor defaults |

For envelopes, inspect `status`, `complete`, and `stopReason` before using the
payload as a complete execution. A stopped job can have valid partial snapshots;
a job with no usable trace can have a null payload. Ordinary budget exhaustion
exits with code `3` without a JSON result. See [bounded tracing](../BOUNDED_TRACING.md)
for the precise statuses, exit codes, and limits. Budgets do not provide isolation.
