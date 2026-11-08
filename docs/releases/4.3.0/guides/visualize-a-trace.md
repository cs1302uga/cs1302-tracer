# Show a trace in a visualizer

The tracer writes JSON and draws nothing itself. This recipe turns a trace into a
memory-diagram image with [**cs1302-code-visualizer**](https://github.com/cs1302uga/cs1302-code-visualizer),
the project's separate visualizer. The commands here were checked with visualizer
release 0.18.10. `generate_visualization --help` lists the options your installed
version accepts, and the visualizer's
[command reference](https://github.com/cs1302uga/cs1302-code-visualizer/blob/main/docs/cli.md)
covers the rest of the tool.

## Before you start

- Install the tracer and work in the directory containing `code-tracer.jar`, as
  in [installation](installation.md).
- Install the visualizer by following its
  [README](https://github.com/cs1302uga/cs1302-code-visualizer#readme). It needs
  **uv**, **Google Chrome**, and Python 3.13 or later, which uv can provision.
  Check that `generate_visualization --help` prints its usage.

Rendering a trace you already have does not run Java, but the first render can
download a browser driver.

## Trace the program

Save this as `Scores.java`:

<!-- visualize-source:start -->
```java
public class Scores {
    public static void main(String[] args) {
        int[] scores = {90, 85, 77};
        String name = "Ada";
        int total = scores[0] + scores[1] + scores[2];
        System.out.println(name + " " + total);
    }
}
```
<!-- visualize-source:end -->

Trace it:

<!-- visualize-trace:start -->
```sh
java -jar code-tracer.jar trace -i Scores.java --remove-main-args --type-style simple > scores.json
```
<!-- visualize-trace:end -->

The command writes Python Tutor JSON, the default format, holding one snapshot
taken when `main` returns. `--remove-main-args` leaves out the empty `args` array,
and `--type-style simple` labels types as `String` rather than `java.lang.String`.
Both match the diagrams the visualizer draws for programs it traces itself.

## Render the diagram

<!-- visualize-render:start -->
```sh
generate_visualization -o scores.png < scores.json
```
<!-- visualize-render:end -->

`scores.png` shows the `main` frame with `scores`, `name`, and `total` set to
`252`. Arrows lead from `scores` to the `int` array `90, 85, 77` and from `name`
to the string `"Ada"`.

`generate_visualization` reads the trace only from standard input. The command
works as shown in **Bash** and **Command Prompt**. In **PowerShell 7.4+**, which
has no `<` operator, run it through Command Prompt:

```powershell
cmd /c "generate_visualization -o scores.png < scores.json"
```

Use PowerShell 7.4+ for the tracing commands as well: Windows PowerShell 5.1 saves
`>` output as UTF-16, which `generate_visualization` cannot read.

Options such as `--dpi 2` and `--theme dark` change the image; run
`generate_visualization --help` for its full list. Its images are always PNG, and
it rejects options that only `code-visualizer` accepts, such as `--format`,
`--no-types`, and `--text-memory-labels`.

## Render every step

Capture every executed line, then render one image per snapshot:

<!-- visualize-steps-trace:start -->
```sh
java -jar code-tracer.jar trace -i Scores.java --all-breakpoints --remove-main-args --type-style simple > steps.json
```
<!-- visualize-steps-trace:end -->

<!-- visualize-steps-render:start -->
```sh
generate_visualization -a -o steps.png < steps.json
```
<!-- visualize-steps-render:end -->

This writes `steps.0.png` through `steps.4.png`, one for each of the five
snapshots, plus `steps.png`, a copy of the last one. In visualizer 0.18.10, each
frame label in these images shows the line number from the last snapshot; read a
snapshot's line from its `line` field in `steps.json` instead.

Chronological capture of a long loop can produce thousands of snapshots, and an
image for each. To capture fewer states, add `--breakpoints` to the command above,
as in the [tracing recipes](tracing.md#choose-snapshots). With `--all-breakpoints`,
the trace keeps every hit of the selected lines, plus the main-exit snapshot, in
one chronological sequence that `-a` renders directly. Without `--all-breakpoints`,
`--breakpoints` keeps only the latest hit of each line and keys the result by line
number; when it names more than one line, pick one with
`generate_visualization -b LINE`.

## Give it the trace payload

The visualizer reads a bare trace payload. Some traces need a change first:

| Trace | What to do |
| --- | --- |
| Ordinary `--format pytutor` output, the default | Render it as shown. |
| A result envelope from `--result-envelope`, a batch response's `result`, or Java `TraceOutcome.json()` | Render only the payload: the envelope's `trace` value, or Java `traceJson()`. A whole envelope fails to render. |
| `--format modern` | Prefer Python Tutor JSON. The visualizer converts modern JSON, but the conversion drops each frame's `this` and thread data, and draws boxed values such as `Integer` as empty boxes. `generate_visualization -a` also renders a modern trace as one image of its last step, so a chronological modern trace does not produce an image per snapshot. |
| `--inline-strings` | Leave the option out, or render with `generate_visualization --string-style inline`. |
| `--multithread` | Not supported. The visualizer ignores thread data and draws only each step's `callStack`. |

To save an envelope's payload, extract its `trace` value with any JSON tool. For
example, with **Python 3**, in any of these shells:

```sh
python3 -c "import json, sys; json.dump(json.load(open(sys.argv[1], encoding='utf-8'))['trace'], open(sys.argv[2], 'w', encoding='utf-8'))" envelope.json scores.json
```

On Windows, use `py` instead of `python3` if `python3` is not found.

A stopped or failed envelope's `trace` can be `null`, or hold only the snapshots
taken before the stop; see [trace payload](../BOUNDED_TRACING.md#trace-payload).

## The visualizer's own tracer

The visualizer can also trace Java source itself, with commands such as
`code-visualizer`. For that, each visualizer release downloads and runs the tracer
release it pins: visualizer 0.18.10 pins tracer 4.2.1 and verifies its SHA-256
checksum. `generate_visualization` traces nothing, so a diagram rendered from your
own JSON reflects the tracer that wrote it.

[Two representations of the same trace](../explanation/trace-model.md#two-representations-of-the-same-trace)
explains why the visualizer prefers Python Tutor JSON.
