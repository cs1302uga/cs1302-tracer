# Read a trace

Use `trace.json` and the unchanged `Main.java` from [your first trace](first-trace.md).
Open both files in a text editor. You will locate the state before and after an
assignment, follow a local reference into the heap, and find the program's output.
You do not need to run another command.

## Follow a local variable into the heap

Check that the root says `"format": "modern"` and `"entryFile": "Main.java"`.
The `sources` map contains the submitted source. Each element of `steps` records
a captured state and identifies its source with `file` and a 1-based `line`.

Find the step whose `file` is `Main.java` and whose `line` is `7`, the assignment
`int answer = box.value;`. Its `callStack` array contains a frame with
`"methodName": "main"`. In that frame's `locals`, find `box`.
There is no `answer` local yet: this stop is before the assignment executes.
Do not treat its absence as a default value of zero.

Now find the later step at line 8, `System.out.println(answer);`. Its `main`
frame includes this local-variable object:

<!-- reading-answer:start -->
```json
{
  "name": "answer",
  "type": "int",
  "value": 7,
  "final": false
}
```
<!-- reading-answer:end -->

This excerpt is one entry in `locals`, not a complete snapshot. The assignment
has copied `7` into `answer`, and the print statement has not executed yet.
Locate states by their source and local values rather than their array positions;
the trace also includes stops inside the `Box` constructor.

Stay in the line-8 snapshot and follow `box`:

1. Find the `box` entry in the `main` frame's `locals`.
2. Its `value` contains a `ref` number. Look up the matching key in that same
   snapshot's `heap` map; JSON object keys are strings.
3. Confirm that the heap entry has `kind` equal to `object` and type `Main$Box`,
   then find this entry in its `fields` array:

<!-- reading-field:start -->
```json
{
  "name": "value",
  "type": "int",
  "value": 7,
  "final": false
}
```
<!-- reading-field:end -->

You have now found the same `7` in two places: the object's `value` field and the
local variable `answer`. `box` refers to the object; it does not contain a copy
of the object. The assignment copies the primitive field value into `answer`.
The local's source-style type `Main.Box` and the heap object's binary name
`Main$Box` both describe this nested class.

Your `ref` number can differ from another run. Compare the reference and its heap
key within your result, rather than looking for a particular number.

## Observe output over time

In the line-8 snapshot, `stdout` is still empty. Find the later main-exit capture
at the closing brace on line 9: its `stdout` contains `7` followed by a newline.
The change confirms that the print executed between those captures.

Output is cumulative. Each snapshot contains the guest output observed up to
that capture, so concatenating every snapshot's `stdout` would duplicate text.
`stderr` similarly records guest stderr. The tracer process writes its own
diagnostics to the terminal's stderr, outside the JSON trace.

At this point, you have connected a source assignment to its local value, followed
an object reference, and found the printed result. For the reasons behind these
representations, read [How a trace represents execution](../explanation/trace-model.md).
The [JSON output reference](../reference/output.md) defines the complete fields
and includes full output examples.

## Observe a repeated breakpoint

Continue with [Trace a loop](trace-a-loop.md). You will visit the same print
statement three times and compare the latest hit, accumulated hits, and
chronological output. The lesson also shows why a selected-breakpoint result
does not have the same root shape as this tutorial's `steps` array.

## Choose the result contract

When choosing output for your own program, use the
[result-contract reference](../reference/output.md#result-contracts) to distinguish
ordinary traces from result envelopes. For other entry points, see the
[batch protocol](../reference/batch.md) and [Java API reference](../reference/java-api.md).
Completion, partial results, budgets, and exit codes are defined in
[bounded tracing](../BOUNDED_TRACING.md).
