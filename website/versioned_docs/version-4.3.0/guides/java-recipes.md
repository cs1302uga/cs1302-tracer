# Java recipes

Use these recipes after the [Java tutorial](../tutorials/java-integration.md). Each
one is a complete program for one task. The
[Java integration reference](../reference/java-api.md) defines every type and
default, and [how an embedding application owns tracing resources](../explanation/java-lifecycle.md)
explains when to choose `Tracer` or `BatchTracer`.

Work in the directory containing `code-tracer.jar`, as in the tutorial. Compile
and run a recipe on Linux or macOS:

<!-- java-recipes-run:start -->
```sh
javac -cp code-tracer.jar ServiceBudgets.java
java -cp "code-tracer.jar:." ServiceBudgets
```
<!-- java-recipes-run:end -->

On Windows, use a semicolon in the run classpath:
`java -cp "code-tracer.jar;." ServiceBudgets`. For the other recipes, replace
`ServiceBudgets` with that recipe's class name.

These recipes trace trusted sample programs. Budgets and field-only inspection
limit what tracing does; they do not isolate guest code. A service that runs
untrusted submissions also needs the [runner contract](../RUNNER_CONTRACT.md).

## Set budgets and field-only inspection

Save this as `ServiceBudgets.java`:

<!-- java-recipes-budgets:start -->
```java
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;

public class ServiceBudgets {
    public static void main(String[] args) {
        String source = """
            import java.util.ArrayList;
            import java.util.List;

            public class Main {
                public static void main(String[] args) {
                    List<String> names = new ArrayList<>();
                    names.add("Ada");
                    System.out.println(names);
                }
            }
            """;
        // Budgets you don't set keep their finite instructor defaults.
        TraceLimits limits = TraceLimits.builder()
            .timeoutMillis(5_000)
            .snapshots(1_000)
            .outputBytes(65_536)
            .heapObjects(1_000)
            .sourceBytes(262_144)
            .build();
        TraceRequest request = TraceRequest.builder(source)
            .limits(limits)
            .inspection(InspectionPolicy.FIELDS)
            .build();
        TraceOutcome outcome = Tracer.trace(request);
        System.out.println(outcome.json());
    }
}
```
<!-- java-recipes-budgets:end -->

The program prints the result envelope with `status` `completed`. Its `limits`
object reports all eleven effective budgets: the five set here, and the instructor
defaults for the rest, such as `elements` at `100000`. Its `diagnostics` note that
`FIELDS` inspection read stored fields, so the trace shows the `ArrayList` as its
`elementData`, `size`, and `modCount` fields rather than as a list.

- `TraceLimits.builder()` starts from the instructor defaults, so any budget you
  don't set stays finite. `TraceLimits.unlimited().toBuilder()` starts with every
  budget off. Zero turns a budget off.
- Prefer the builder to the eight-argument `TraceLimits` constructor, which leaves
  the `threads`, `frames`, and `snapshotBytes` budgets unlimited.
- The timeout covers the trace phase only, not reading or compiling source or
  starting a reusable guest. See [time budgets](../explanation/java-lifecycle.md#time-budgets-cover-tracing-not-preparation).

[Limits](../BOUNDED_TRACING.md#limits) define each budget, and
[inspection policies](../explanation/inspection-policies.md) explain what `FIELDS`
gives up.

## Supply input and several source files

Save a submission's two files in a `submission` folder.
`submission/demo/Main.java`:

<!-- java-recipes-main:start -->
```java
package demo;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        String name = in.nextLine();
        int times = in.nextInt();
        System.out.println(Greeter.greet(name, times));
    }
}
```
<!-- java-recipes-main:end -->

`submission/demo/Greeter.java`:

<!-- java-recipes-greeter:start -->
```java
package demo;

class Greeter {
    static String greet(String name, int times) {
        return ("Hello, " + name + "! ").repeat(times).trim();
    }
}
```
<!-- java-recipes-greeter:end -->

Save this as `BundleInput.java`:

<!-- java-recipes-bundle:start -->
```java
import cs1302.tracer.api.SourceBundle;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import java.io.IOException;
import java.nio.file.Path;

public class BundleInput {
    public static void main(String[] args) throws IOException {
        // Read every .java file under submission, named by its path inside that folder.
        SourceBundle bundle = SourceBundle.builder()
            .addFiles(Path.of("submission"), "**/*.java")
            .build();
        TraceRequest request = TraceRequest.builder(bundle.source())
            .stdin("Ada\n2\n")
            .build();
        TraceOutcome outcome = Tracer.trace(request);
        System.err.print(outcome.stderr());
        System.out.println(outcome.status());
        System.out.print(outcome.stdout());
    }
}
```
<!-- java-recipes-bundle:end -->

It prints:

<!-- java-recipes-bundle-output:start -->
```text
completed
Hello, Ada! Hello, Ada!
```
<!-- java-recipes-bundle-output:end -->

`addFiles` reads every matching file when it is called, in path order, and names
each one by its path inside `submission`. The traced program reads the `stdin`
string as standard input: `Ada`, then `2`.

- To add source text you already hold, use `addSource("demo/Main.java", text)`, or
  `addInferredSource(text)` to name the file from its package and public class.
  [Bundle source files](../reference/java-api.md#bundle-source-files) lists every
  method.
- `bundle.source()` returns a [delimited source bundle](../reference/source-format.md),
  which you can also write yourself.
- File-qualified breakpoints name a file by its package directory and file name,
  such as `demo/Main.java:10`, whatever folder it came from.
  [Discover breakpoints](../reference/java-api.md#discover-breakpoints) lists the
  valid locations.
- If the input runs out, `Scanner` throws inside JDK code. A single-stack trace
  can then still report `completed`, with the stack trace only in `stderr`, which
  is why the program forwards `stderr`. See
  [uncaught exceptions](../reference/batch.md#uncaught-student-exceptions).

## Choose the format, captured states, and presentation

Save this as `FormatChoices.java`:

<!-- java-recipes-format:start -->
```java
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import cs1302.tracer.api.TypeStyle;

public class FormatChoices {
    public static void main(String[] args) {
        String source = """
            public class Main {
                public static void main(String[] args) {
                    String label = "total";
                    int total = 0;
                    for (int i = 1; i <= 3; i++) {
                        total += i;
                    }
                    System.out.println(label + " " + total);
                }
            }
            """;
        TraceRequest request = TraceRequest.builder(source)
            .format(TraceFormat.MODERN)
            .chronological(true)
            .typeStyle(TypeStyle.SIMPLE)
            .removeMainArgs(true)
            .build();
        // true adds binary metadata to primitive values.
        TraceOutcome outcome = Tracer.trace(request, true);
        System.out.println(outcome.traceJson());
    }
}
```
<!-- java-recipes-format:end -->

The program prints the modern trace payload alone, without the envelope. Its
`steps` array holds one snapshot per executed line. In the last step, the `main`
frame has no `args` variable, `label` has type `String` rather than
`java.lang.String`, and `total` carries `binary` metadata beside its value `6`.

| Choice | Request setting | Effect |
| --- | --- | --- |
| Format | `format(TraceFormat.MODERN)` | Modern JSON instead of the default Python Tutor JSON. See [two representations](../explanation/trace-model.md#two-representations-of-the-same-trace). |
| Captured states | `chronological(true)`, `breakpoints(List.of("6"))`, `accumulate(true)` | The default is one snapshot when `main` returns. See [result contracts](../reference/output.md#result-contracts). |
| Threads | `multithread(true)` | Every application thread's stack. Requires `MODERN`, implies chronological capture and `FIELDS` inspection, and runs in a fresh dedicated guest that keeps tracing after `main` returns until non-daemon threads finish. See [thread capture](../reference/output.md#opt-in-thread-capture). |
| Type labels | `typeStyle(TypeStyle.SIMPLE)` | Simple class names in type labels. |
| Hidden variables | `removeMainArgs(true)`, `removeMethodThis(true)` | Leave out `main`'s `args` or each method's `this`. |
| Inline strings | `inlineStrings(true)` | Python Tutor output only; modern output ignores it. |
| Primitive bits | `Tracer.trace(request, true)` | Adds [binary metadata](../reference/output.md#optional-primitive-binary-metadata) to primitive values. |

`toBuilder()` copies a request, so you can change one setting, such as the
format, and keep the rest.

## Handle completed, stopped, and failed outcomes

Save this as `HandleOutcomes.java`:

<!-- java-recipes-outcomes:start -->
```java
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;

public class HandleOutcomes {
    static String describe(TraceOutcome outcome) {
        long kept = outcome.counters().getOrDefault("snapshotsRetained", 0L);
        return switch (outcome.status()) {
            case "completed" -> "completed; snapshots kept: " + kept;
            case "stopped" -> "stopped by " + outcome.stopReason() + "; snapshots kept: " + kept;
            case "failed" -> "failed with " + outcome.stopReason() + "; snapshots kept: " + kept;
            default -> throw new IllegalStateException("Unknown status: " + outcome.status());
        };
    }

    public static void main(String[] args) {
        String loop = """
            public class Main {
                public static void main(String[] args) {
                    for (int i = 1; i <= 5; i++) {
                        System.out.println(i);
                    }
                }
            }
            """;
        String broken = """
            public class Main {
                public static void main(String[] args) {
                    int count = "five";
                }
            }
            """;
        TraceRequest limited = TraceRequest.builder(loop)
            .chronological(true)
            .limits(TraceLimits.builder().snapshots(3).build())
            .build();
        System.out.println(describe(Tracer.trace(new TraceRequest(loop))));
        System.out.println(describe(Tracer.trace(limited)));
        System.out.println(describe(Tracer.trace(new TraceRequest(broken))));
    }
}
```
<!-- java-recipes-outcomes:end -->

It prints:

<!-- java-recipes-outcomes-output:start -->
```text
completed; snapshots kept: 1
stopped by snapshot_limit; snapshots kept: 3
failed with compile_error; snapshots kept: 0
```
<!-- java-recipes-outcomes-output:end -->

Branch on `status()` and `stopReason()`:

- **`completed`:** tracing finished with no stop reason, and `complete()` is true.
  Still check `stderr()`: an exception thrown inside JDK code can leave a
  single-stack trace `completed`.
- **`stopped`:** a budget, the timeout, or another controlled stop ended tracing.
  The payload keeps the snapshots completed before the stop, a partial trace.
  Here chronological capture kept three. Default capture takes its one snapshot
  when `main` returns, so a stop before then keeps none; capture chronologically
  or at selected lines when partial progress matters.
- **`failed`:** compilation or dependency preparation failed, the request's
  settings were rejected (`validation_error`, such as `multithread(true)` without
  `MODERN`), the program threw or exited, or tracing failed. `diagnostics()`
  explains why. Compiler messages can include temporary host paths, so review
  them before showing them to the program's author.

Count kept snapshots with the `snapshotsRetained` counter. Don't infer progress
from `traceJson()`: it is the JSON literal `null` when no payload exists, and a
payload can also be empty. Don't classify outcomes by `phase()`, which varies by
entry point. To store or forward a result, `json()` returns the whole envelope and
`writeJson(writer)` writes it.

[Stop codes](../BOUNDED_TRACING.md#stop-codes) list every `stopReason`, and the
[envelope](../BOUNDED_TRACING.md#envelope) defines every field.

## Process many jobs with BatchTracer

`Tracer.trace` starts a guest JVM for every call. A `BatchTracer` keeps a pool of
workers whose guest JVMs run many single-stack jobs in turn, which saves a JVM
start per job. It reads [batch requests](../reference/batch.md) as NDJSON, so this
recipe converts each `TraceRequest` into one request line.

The recipe uses **Gson** to write and read JSON. Use your application's own JSON
library: `code-tracer.jar` happens to contain Gson classes, which is why this
example compiles beside it, but they are not part of the tracer's supported
interface. See [compile-time dependencies](../reference/java-api.md#stability-and-compile-time-dependencies).

Save this as `BatchJobs.java`:

<!-- java-recipes-batch:start -->
```java
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class BatchJobs {
    // Writes every request setting, because omitted batch fields have batch defaults.
    static JsonObject toBatchRequest(String id, TraceRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("source", request.source());
        json.addProperty("stdin", request.stdin());
        json.addProperty("format", request.format().toString());
        JsonArray breakpoints = new JsonArray();
        request.breakpoints().forEach(breakpoints::add);
        json.add("breakpoints", breakpoints);
        json.addProperty("allBreakpoints", request.chronological());
        json.addProperty("accumulateBreakpoints", request.accumulate());
        json.addProperty("multithread", request.multithread());
        json.addProperty("removeMainArgs", request.removeMainArgs());
        json.addProperty("inlineStrings", request.inlineStrings());
        json.addProperty("removeMethodThis", request.removeMethodThis());
        json.addProperty("typeStyle", request.typeStyle().toString());
        json.addProperty("inspection", request.inspection().name());
        TraceLimits limits = request.limits();
        JsonObject budgets = new JsonObject();
        budgets.addProperty("timeoutMillis", limits.timeoutMillis());
        budgets.addProperty("snapshots", limits.snapshots());
        budgets.addProperty("outputBytes", limits.outputBytes());
        budgets.addProperty("heapObjects", limits.heapObjects());
        budgets.addProperty("elements", limits.elements());
        budgets.addProperty("traceBytes", limits.traceBytes());
        budgets.addProperty("sourceBytes", limits.sourceBytes());
        budgets.addProperty("sourceFiles", limits.sourceFiles());
        budgets.addProperty("threads", limits.threads());
        budgets.addProperty("frames", limits.frames());
        budgets.addProperty("snapshotBytes", limits.snapshotBytes());
        json.add("limits", budgets);
        return json;
    }

    public static void main(String[] args) throws IOException {
        Map<String, TraceRequest> jobs = new LinkedHashMap<>();
        jobs.put("ada", new TraceRequest("""
            public class Main {
                public static void main(String[] args) {
                    System.out.println(6 * 7);
                }
            }
            """));
        jobs.put("loop", TraceRequest.builder("""
            public class Main {
                public static void main(String[] args) {
                    for (int i = 1; i <= 5; i++) {
                        System.out.println(i);
                    }
                }
            }
            """).chronological(true).limits(TraceLimits.builder().snapshots(3).build()).build());

        Gson gson = new Gson();
        StringBuilder ndjson = new StringBuilder();
        jobs.forEach((id, request) -> ndjson.append(gson.toJson(toBatchRequest(id, request))).append('\n'));
        InputStream requests = new ByteArrayInputStream(ndjson.toString().getBytes(StandardCharsets.UTF_8));
        StringWriter responses = new StringWriter();
        try (BatchTracer tracer = BatchTracer.builder()
                .workers(2)
                .maxJobsPerWorker(100)
                .maxInFlight(4)
                .build()) {
            tracer.processStream(requests, responses);
        }

        for (String line : responses.toString().lines().toList()) {
            JsonObject response = JsonParser.parseString(line).getAsJsonObject();
            JsonElement id = response.get("id");
            JsonObject result = response.getAsJsonObject("result");
            JsonElement reason = result.get("stopReason");
            System.out.println((id.isJsonNull() ? "unparsed request" : id.getAsString()) + ": "
                + result.get("status").getAsString()
                + (reason.isJsonNull() ? "" : " (" + reason.getAsString() + ")"));
        }
    }
}
```
<!-- java-recipes-batch:end -->

It prints:

<!-- java-recipes-batch-output:start -->
```text
ada: completed
loop: stopped (snapshot_limit)
```
<!-- java-recipes-batch-output:end -->

`toBatchRequest` writes every setting because an omitted batch field takes the
batch protocol's default, not the `TraceRequest` default. In particular, an
omitted budget is [unlimited](../reference/batch.md#request-limits). Where the
names or values differ:

| `TraceRequest` setting | Batch request field |
| --- | --- |
| `chronological` | `allBreakpoints` |
| `accumulate` | `accumulateBreakpoints` |
| `format`, `typeStyle` | The lowercase token from `toString()`, such as `modern` or `simple`. |
| `inspection` | The constant name, `TRUSTED` or `FIELDS`. This field is case-sensitive; any other token silently selects `TRUSTED`. |
| `limits` | A `limits` object with all eleven budget names as keys. |
| `Tracer.trace(request, true)` | `includePrimitiveBits: true` |
| A `TraceClassPath` | `classPath`, `copyClassPath`, and `followClassPathLinks`; see [binary dependencies](../reference/batch.md#binary-dependencies). |

Under `TRUSTED` inspection, a batch job can also find matching source roots in
the process working directory, which `Tracer.trace` never does. Include every
source file in the request to get the same compilation from both.

- Responses arrive in request order. `completionOrder(true)` writes each one as
  its job finishes instead, so match responses by `id`. A request that could not
  be parsed gets a null `id`.
- `processStream` returns after every response is written. It throws
  `IOException` only for stream failures; failed jobs produce ordinary responses.
- Run one `processStream` call at a time on a processor, and close the processor
  when done, as the `try` block does. The processor never closes your streams.
  For files, pass `Files.newInputStream` and `Files.newBufferedWriter` streams.
- A worker's guest JVM runs up to `maxJobsPerWorker` jobs before it is replaced.
  `maxJobsPerWorker(1)` gives every job a fresh guest; see
  [guest reuse](../reference/batch.md#guest-reuse-between-jobs).

[Process a batch stream](../reference/java-api.md#process-a-batch-stream) and
[I/O failures and shutdown](../reference/java-api.md#io-failures-and-shutdown)
define the full contract.
