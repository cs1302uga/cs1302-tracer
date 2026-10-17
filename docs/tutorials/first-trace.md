# Your first trace

Create a Java program, capture its execution, and save the JSON result. The tracer
produces data for a visualizer; it does not open an interactive visualization.
First complete [installation](../guides/installation.md) with a full **JDK 21 or later**.

## Choose your working directory

Run the commands below in the directory containing `code-tracer.jar`:

- Binary archive: enter the extracted `code-tracer` directory.
- Standalone download: use the directory where you saved the JAR.
- Source build: from the repository root, run `cd target` after packaging.

All commands in this tutorial use direct Java invocation and work in **Bash**,
**PowerShell**, and **Command Prompt**. Launchers are an alternative once this works.

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
and prints `7`. The tracer compiles it for you.

## Run and save the trace

<!-- first-command:start -->
```sh
java -jar code-tracer.jar trace -i Main.java --all-breakpoints --format modern --pretty > trace.json
```
<!-- first-command:end -->

Successful execution exits with status `0` and writes a JSON document to `trace.json`.
Open that file in a text editor. Look for `format: "modern"`, `entryFile: "Main.java"`,
and a nonempty `steps` array. The final captured `stdout` contains `7` followed by a
newline. It is inside the JSON, so the program's `7` is not printed separately to
your terminal. Tracer diagnostics go to stderr and remain visible with this command.

`--all-breakpoints` retains snapshots in execution order. Without it or selected
breakpoints, the default is a main-exit snapshot. `--pretty` changes JSON formatting,
not which states are captured. Ordinary tracing uses finite default budgets; see
[limits and partial results](../BOUNDED_TRACING.md) before changing them.

## Choose what to explore next

- [Read a trace](read-a-trace.md): connect source lines, variables, and heap objects.
- [Tracing programs](../guides/tracing.md): select breakpoints, provide input, and submit multiple files.
- [Run batch jobs](batch-jobs.md): send several requests and inspect each result.
- [Troubleshooting](../guides/troubleshooting.md): diagnose installation and tracing failures.

The tracer executes the supplied program. Run trusted programs directly on your
workstation; hosted untrusted submissions require the [runner contract](../RUNNER_CONTRACT.md).
