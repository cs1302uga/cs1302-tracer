# Your first trace

Create a Java program, capture its execution, and save a JSON file you can inspect.
By the end, you will have a trace showing a local variable that refers to an object,
a value copied from that object's field, and the program's printed output.
The tracer produces data for a visualizer; it does not open an interactive
visualization. First complete [installation](../guides/installation.md) with a full
**JDK 21 or later**. You do not need a source checkout or a separate JSON tool.

## Choose your working directory

Run the commands below in the directory containing `code-tracer.jar`:

- Binary archive: enter the extracted `code-tracer` directory.
- Standalone download: use the directory where you saved the JAR.
- Source build: from the repository root, run `cd target` after packaging.

All commands in this tutorial use direct Java invocation and work in **Bash**,
**PowerShell 7.4+**, and **Command Prompt**. Launchers are an alternative once this works.
Before continuing, check that you can see `code-tracer.jar` in this directory.

## Create a program

Save this exact text as `Main.java` in that directory:

<!-- first-source:start -->
```java
public class Main {
    static class Box {
        int value = 7;
    }
    public static void main(String[] args) {
        Box box = new Box();
        int answer = box.value;
        System.out.println(answer);
    }
}
```
<!-- first-source:end -->

This trusted example creates a `Box` object, copies its field into a local variable,
and prints `7`. The tracer compiles it for you. Keep the text and line breaks as
shown so the next lesson can refer to the same source lines. Your directory should
now contain both `Main.java` and `code-tracer.jar`.

## Run and save the trace

<!-- first-command:start -->
```sh
java -jar code-tracer.jar trace -i Main.java --all-breakpoints --format modern --pretty > trace.json
```
<!-- first-command:end -->

Successful execution exits with status `0` and writes a JSON document to `trace.json`.
Open that file in a text editor and check these three things:

1. The root has `"format": "modern"` and `"entryFile": "Main.java"`.
2. The `sources` map contains `Main.java`, and `steps` is a nonempty array.
3. The final captured `stdout` contains `7` followed by a newline, displayed as
   an escaped newline in the JSON string.

The program's `7` is inside the JSON, so it is not printed separately to your
terminal. Tracer diagnostics go to stderr and remain visible with this command.
If `trace.json` is empty or the command reports an error, use
[troubleshooting](../guides/troubleshooting.md) before continuing. An empty file
can be created by shell redirection even when tracing fails.

`--all-breakpoints` retains snapshots in execution order. Without it or selected
breakpoints, the default is a main-exit snapshot. Ordinary modern output is already
indented; `--pretty` also requests readable formatting for Python Tutor output and
result envelopes. Formatting does not select which states are captured.
Ordinary tracing uses finite default budgets; see
[limits and partial results](../BOUNDED_TRACING.md) before changing them.

## Choose what to explore next

Continue with [Read a trace](read-a-trace.md) to find the assignment in your new
file and follow `box` from a local variable to its heap object. After that:

- [Trace a loop](trace-a-loop.md): compare repeated visits to one source line.
- [Tracing programs](../guides/tracing.md): select breakpoints, provide input, and submit multiple files.
- [Run batch jobs](batch-jobs.md): send several requests and inspect each result.
- [Troubleshooting](../guides/troubleshooting.md): diagnose installation and tracing failures.

The tracer executes the supplied program. Run trusted programs directly on your
workstation; hosted untrusted submissions require the [runner contract](../RUNNER_CONTRACT.md).
