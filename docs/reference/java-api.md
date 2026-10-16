# Java integration and 4.0 migration

The supported Java interface is `cs1302.tracer.api`, plus the stable CLI launcher
`cs1302.tracer.App`. Use the regular tracer JAR for library integration and supply
its Maven runtime dependencies. The dependency-bundled JAR remains the executable
CLI distribution. Both declare the automatic module name `cs1302.tracer`.

Public signatures use project-owned types and ordinary Java types. API Guardian's
`@API` annotation is the sole third-party metadata exception. Callers do
not construct JavaParser ASTs, configure Gson, handle Picocli commands, or retain
live JDI objects. Those dependencies remain necessary at runtime; this change
removes them from the supported interface, not from the implementation.

## Stability and compile-time dependencies

Supported API types and the launcher declare
`@API(status = API.Status.STABLE, since = "4.0.0")`: their supported members will
not change incompatibly within the 4.x series. Public implementation types declare
`INTERNAL` and carry no external compatibility promise. A type's status applies
to its public members unless a member explicitly declares an exception.

The `since` value records when the stability status began, not necessarily when
the code was introduced. Version 4.0.0 formalizes this boundary, including for the
older launcher and implementation code moved into internal packages.

`org.apiguardian:apiguardian-api:1.1.2` is a transitive compile dependency. Maven
consumers receive it automatically; when compiling against the regular JAR by
hand, also include the API Guardian JAR on the compile classpath to avoid missing
annotation warnings. No JavaParser, Gson, Picocli, or JDI implementation objects
are required in consumer source. Runtime dependencies remain necessary to execute
the tracer. The API Guardian exception is confined to annotations: its types are
not permitted as supported parameter, return, or field types.

## Trace a submitted program

```java
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;

TraceOutcome outcome = Tracer.trace(new TraceRequest("""
    public class Main {
        public static void main(String[] args) {
            System.out.println("hello");
        }
    }
    """));
System.out.println(outcome.status());
System.out.print(outcome.stdout());
String envelope = outcome.json();
String payload = outcome.traceJson();
```

The convenience request uses Python Tutor output, trusted inspection, finite
instructor limits, and main-exit selection. The full `TraceRequest` constructor
accepts stdin, format, breakpoint specifications, chronological/accumulated capture,
multithread mode, presentation options, limits, and inspection policy. It copies
the breakpoint list. Required reference values must be non-null; use an empty
string for absent stdin and an empty list for main-exit selection. With chronological
capture, an empty breakpoint list selects all executable lines.

Source input is self-contained text, including the existing delimited multi-file
syntax. Read filesystem input before constructing the request, or use the CLI
when its neighboring-source discovery behavior is needed. The one-shot operation
uses the same job execution implementation as batch tracing and closes its worker
before returning. Its payload follows batch/result-envelope trace conventions;
it does not reproduce ordinary CLI selected-breakpoint map presentation.

Expected compilation and execution failures become outcomes. `status`, `phase`,
`stopReason`, and `diagnostics` explain failures or resource stops; `complete`
indicates successful completion. `json()` returns the existing result envelope,
while `traceJson()` returns its payload, or the JSON literal `null` when unavailable.
Both are serialized before cleanup. Metadata collections are immutable, and an
outcome remains valid after later jobs. `writeJson(Writer)` writes the envelope
without flushing or closing the caller's writer and propagates `IOException`.

The Java result deliberately exposes JSON text rather than untyped object graphs
or serializer nodes. Parse that JSON with the consumer's chosen library if needed.
A typed snapshot interface is not currently part of the supported contract.

## Discover breakpoints

`Tracer.discoverBreakpoints(source)` compiles self-contained submitted sources and
reads executable line tables without running guest code. The returned
`BreakpointDiscovery` contains immutable `BreakpointLocation` values, ordered by
source path and line, or preparation diagnostics. Locations use package-relative
paths and one-based lines. A preparation failure has diagnostics and no locations.
Temporary compilation resources are released before the operation returns.

## Process a batch stream

```java
import cs1302.tracer.api.BatchTracer;
import java.io.InputStream;
import java.io.Writer;

// requests and responses are caller-owned InputStream and Writer instances.
try (BatchTracer tracer = new BatchTracer(2, 100, 16, false)) {
    tracer.processStream(requests, responses);
}
```

Constructor settings are worker count, jobs before recycling, maximum in-flight
requests, and completion-order output. Use `false` for submission order. The
processor owns the worker pool and guest cleanup; call `close()` or use
try-with-resources. Stream processing flushes responses but leaves caller-owned
streams open. Call `processStream` sequentially and never use a closed processor.
The existing [batch protocol](batch.md), defaults, validation, and per-job result
formats are unchanged. The CLI `batch-trace` command uses this same public operation.

## Migrate Java integrations from 3.x

This is a breaking Java interface change for 4.0.0. CLI syntax and serialized
Python Tutor, modern, envelope, and NDJSON contracts remain unchanged.

| Previous integration | Replacement |
| --- | --- |
| `CompilationHelper`, `SourcePreparation`, and `App.parseSource` | Submit source text to `Tracer`; compilation and AST ownership are internal. |
| `DebugTraceHelper` and JDI-based extraction helpers | `Tracer.trace(TraceRequest)` for self-contained jobs. |
| `getValidBreakpointLines` and compiled-program inspection | `Tracer.discoverBreakpoints(source)`. |
| `PyTutorSerializer.getGson()` or `ModernTraceSerializer.getGson()` | `TraceOutcome.json()`, `traceJson()`, or `writeJson(Writer)`. |
| `BatchTraceService` | `BatchTracer` with the same stream protocol. |
| `execution.TraceLimits` / `execution.InspectionPolicy` | Corresponding types in `cs1302.tracer.api`. |
| `model.TraceFormat` / `model.TypeStyle` | Corresponding types in `cs1302.tracer.api`. |
| Internal snapshot or wire records | Consume the documented JSON contract; internal records are not supported DTOs. |

Implementation classes now live under `cs1302.tracer.internal`. Some must remain
public for communication across implementation packages and guest JVM launching.
Their visibility does not make them supported library interfaces. There are no
compatibility aliases for the old parser/debugger/serializer helpers.

`Automatic-Module-Name` supplies stable module identity, not access control:
automatic modules export all packages, and classpath execution likewise does not
hide public internal classes. Explicit JPMS exports are deferred; doing that
requires validating dependency module names, Gson reflective access, and guest
launching independently for the regular JAR and executable assembly. The build
instead checks the supported declarations for dependency leakage and compiles an
external consumer with only tracer classes and API Guardian on its compile classpath.

Generated public Javadoc omits implementation packages. The repository's internal
package documentation and dependency links remain useful for contributor builds.
The tracer executes guest code and is not a security sandbox; use the separate
[runner contract](../RUNNER_CONTRACT.md) for isolation requirements.
