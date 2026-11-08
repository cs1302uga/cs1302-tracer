# Java integration reference

Start with the [complete Java tutorial](../tutorials/java-integration.md) for a runnable example.
This reference defines the supported contracts and advanced configuration.

The supported Java interface is `cs1302.tracer.api`, plus the stable CLI launcher
`cs1302.tracer.App`. Use the regular tracer JAR for library integration and supply
its **Maven** runtime dependencies. The dependency-bundled JAR remains the executable
CLI distribution. Both declare the automatic module name `cs1302.tracer`.
For offline **Javadoc**, use the matching release's
`cs1302-tracer-VERSION-javadoc.jar` when available; it contains API HTML rather
than runtime classes. See [offline API documentation](../guides/installation.md#offline-api-documentation)
for its checksum/provenance companions, verification, and extraction. The hosted
API reference remains available through the documentation site's version menu.
Run with a full **JDK 21 or later**; CI exercises Java 21 and 25, and `.java-version`
selects the local development JDK. Submitted-program features depend on the active
JDK and parser support.

This documentation describes development version `4.2.0-SNAPSHOT`. Features marked 4.1.0 include
`SourceBundle`, `TraceClassPath`, the builders, and the primitive binary metadata
overload.

Public signatures use project-owned types and ordinary Java types. API Guardian's
`@API` annotation is the sole third-party metadata exception. Callers do
not construct JavaParser ASTs, configure Gson, handle Picocli commands, or retain
live JDI objects. Those dependencies remain necessary at runtime; this change
removes them from the supported interface, not from the implementation.

## Stability and compile-time dependencies

Supported API types and the launcher declare API Guardian `STABLE`: their supported
members will not change incompatibly within the 4.x series. Existing types date
that status to 4.0.0; `SourceBundle`, `TraceClassPath`, and the builders introduced in 4.1.0 date it to 4.1.0.
Public implementation types declare
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
        } // main

    } // Main
    """));
System.out.println(outcome.status());
System.out.print(outcome.stdout());
String envelope = outcome.json();
String payload = outcome.traceJson();
```

The convenience request uses Python Tutor output, trusted inspection, finite
instructor limits, main-exit selection, empty stdin, and fully qualified type labels.
All boolean components default to false. The full `TraceRequest` constructor
accepts stdin, format, breakpoint specifications, chronological/accumulated capture,
multithread mode, presentation options, limits, and inspection policy. It copies
the breakpoint list. Required reference values must be non-null; use an empty
string for absent stdin and an empty list for main-exit selection. With chronological
capture, an empty breakpoint list selects all executable lines.
With a nonempty selection, chronological capture retains hits at those locations
in execution order. Multithread capture requires modern output and uses chronological
capture and field-only inspection, while preserving the requested record components.
An incompatible format becomes a validation failure when traced. Source and breakpoint
text are not parsed during request construction; breakpoint parsing follows successful
compilation, so compilation diagnostics take precedence over malformed breakpoint text.

`InspectionPolicy.TRUSTED` can invoke guest methods for specialized collection,
map, wrapper, and stream inspection. `FIELDS` reads ordinary objects through fields
and avoids inspection-time method calls; arrays, strings, and `java.awt.Color` retain dedicated
representations. Neither policy isolates guest code. `TypeStyle.FQN` preserves
qualified type labels; `SIMPLE` removes dotted prefixes for presentation without
resolving Java types. Both settings also apply to the return and parameter types
in [reconstructed lambda signatures](output.md#lambda-implementation-types),
including generic arguments and arrays, while preserving the lambda body's text.

Use the overload `Tracer.trace(request, true)` to include
[primitive binary metadata](output.md#optional-primitive-binary-metadata) in either
output format. For example:

```java
TraceRequest request = TraceRequest.builder(source)
    .format(TraceFormat.MODERN)
    .chronological(true)
    .inspection(InspectionPolicy.FIELDS)
    .build();
TraceOutcome outcome = Tracer.trace(request, true);
```

Here `source` is submitted Java text and the named types are in `cs1302.tracer.api`.
`Tracer.trace(request)` delegates with false, preserving existing output.
The published `TraceRequest` record components and constructors are unchanged;
existing record patterns, reflective consumers, and compiled callers remain valid.
The additional option affects rendering only, not capture or inspection policy.
See [encoding definitions and references](output.md#encoding-definitions-and-references)
for the binary formats' normative specifications and informative explanations.

Source input is submitted text, including the existing delimited multi-file
syntax. Use [`SourceBundle`](#bundle-source-files) to assemble text or capture files
before constructing the request, or use the CLI
when its neighboring-source discovery behavior is needed. Supply separately compiled
dependencies through [`TraceClassPath`](#binary-dependencies). The one-shot operation
uses the same job execution implementation as batch tracing and closes its worker
before returning. Its payload follows batch/result-envelope trace conventions;
it does not reproduce ordinary CLI selected-breakpoint map presentation.

Expected compilation and execution failures become outcomes. `status`, `phase`,
`stopReason`, and `diagnostics` explain failures or resource stops; `complete`
indicates successful completion. `json()` returns the existing result envelope,
while `traceJson()` returns its payload, or the JSON literal `null` when unavailable.
Both JSON strings are materialized before the operation returns. Metadata
collections are immutable, and an outcome remains valid after guest cleanup and
later jobs. `writeJson(Writer)` writes the envelope
without flushing or closing the caller's writer and propagates `IOException`.

The Java result deliberately exposes JSON text rather than untyped object graphs
or serializer nodes. Parse that JSON with the consumer's chosen library if needed.
A typed snapshot interface is not currently part of the supported contract.

## Binary dependencies

Since 4.1.0, `TraceClassPath` describes ordered local directories and JARs for
compilation, source type analysis, and guest execution. It is separate from
`TraceRequest`, whose record components and constructors remain unchanged. For
example, when `build/classes` contains package-relative class files and `lib`
contains dependency JARs:

```java
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import java.nio.file.Path;

TraceClassPath dependencies = TraceClassPath.builder()
    .addPath(Path.of("build/classes"))
    .addGlob(Path.of("lib"), "**/*.jar")
    .copy(true)
    .build();

TraceOutcome outcome = Tracer.trace(TraceRequest.builder(source).build(), dependencies);
```

`addPath(Path)` is always literal, including path-list separators and glob
metacharacters in the filename. A directory is a root containing package-relative
`.class` files and resources; adding it does not request compilation of its Java
sources or add JARs stored inside it. A regular file must be a readable JAR archive,
regardless of its filename suffix. All entries use the host filesystem. There is
no Maven resolution, module-path configuration, or separate compile/runtime list.
Source-discovery roots are not implicit binary dependency entries; add them
explicitly when they also contain precompiled classes required by the program.

`addGlob(root, pattern)` uses the same portable syntax as
[`SourceBundle` glob selection](#bundle-source-files), with no implicit extension
filter. `**/*.jar` matches lowercase `.jar` files at the root and at any depth;
`*.{jar,JAR}` selects immediate `.jar` and `.JAR` files. Hidden regular files
participate. Each selected file must be a valid dependency archive. The builder
validates structure and glob syntax without opening paths or selecting files.
`entries()` returns an immutable ordered list of `PathEntry` and `GlobEntry`
values; `toBuilder()` starts an independent mutable copy. Builders are reusable
and not thread-safe. An empty configuration is valid; `TraceClassPath.empty()`
also disables copying and link following.

Each tracing or discovery call resolves relative paths against the tracer
process working directory during preparation. It expands each glob, sorts that
group by case-sensitive, locale-independent relative path, and inserts it at the
selection's position. The first occurrence of each normalized absolute path wins.
Physical aliases and distinct files with identical contents remain separate.
The job's compiled output precedes dependencies among application entries; JDK
classes retain platform precedence. Classes and resources use the same prepared
dependency order, and source type analysis uses dependency bytecode without
loading dependency classes into the host tracer. Type evidence remains
conservative; this does not guarantee recovery of erased generic arguments.

Local JAR manifest `Class-Path` references are followed transitively in their
specified search order. Relative manifest URLs resolve against the originating
JAR, including when its bytes are copied elsewhere. Absolute local `file:`
references, including `file://localhost/` URLs, are supported. Missing manifest
targets and invalid or non-file URLs are ignored. Manifest directory URLs must end
with `/`, such as `classes/`; a directory named without the slash or an archive
named with one is ignored. This URL distinction does not apply to literal
`addPath(Path)` entries, whose type comes from the filesystem. Missing explicitly
supplied paths, unreadable dependencies, invalid archives, and globs with no matches
fail preparation. Manifest references use URL syntax, not CLI glob syntax. See the
[JAR `Class-Path` specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html#class-path-attribute).

Borrowing is the default: keep caller-owned dependencies available and unchanged
until the operation returns. `copy(true)` captures fresh dependencies before
compilation on every call, including the available transitive manifest closure.
Compilation, type analysis, and guest lookup then use those copies. Original JAR
bytes, manifests, and signatures are preserved; copied lookup does not follow
manifest references back to original files. Closing the operation removes owned
copies and compiled output, never borrowed inputs. This is not an atomic snapshot
of concurrently changing files. A built configuration retains selections, rather
than the eagerly captured contents provided by `SourceBundle`.

`followLinks(false)` is the default. Glob discovery skips symbolic links encountered
below the root; directory copying rejects encountered links. Explicitly supplied
paths and selected roots retain normal filesystem resolution. `followLinks(true)`
follows and captures encountered targets, including targets outside a selected
directory, and fails on broken links or directory cycles. Manifest graph cycles
terminate through first-occurrence handling instead of causing a directory-cycle error.

Use `Tracer.trace(request, dependencies, true)` to include primitive binary
metadata, or `Tracer.discoverBreakpoints(source, dependencies)` to compile and
discover submitted-program locations without launching a guest. Existing overloads
use an empty dependency configuration. Null dependency configurations are rejected.
Dependency preparation failures use the existing compile/preparation diagnostics;
they do not change trace JSON schemas or envelope versions. Precompiled dependencies
receive no source-line breakpoints. Their objects and frames can still appear under
the existing capture rules, including callbacks into submitted code.

Guest compilation explicitly uses `-proc:none`; annotations remain available as
types, but annotation processors are not executed. Supply generated declarations
as sources when required. JAR signature verification, package sealing, multi-release
selection for the active JDK, resource lookup, and service discovery apply through
the job's defining/context loaders. Do not assume that `getSystemClassLoader()`,
`java.class.path`, or a library's independently created loaders describe the same
application class path across dedicated and persistent guests.

Source byte/file limits do not bound dependency archives or copied resource trees.
Tracing timeouts are not whole-job preparation deadlines. Hosted integrations must
control which host paths jobs may select and provide whole-job isolation under the
[runner contract](../RUNNER_CONTRACT.md); copying dependencies does not isolate guest code.

## Bundle source files

Since 4.1.0, `SourceBundle` builds immutable source text in the existing delimiter
format. Add files in the intended order, then pass `bundle.source()` to the existing
request builder; no new tracing overload is required. For example:

```java
import cs1302.tracer.api.SourceBundle;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;

SourceBundle bundle = SourceBundle.builder()
    .addSource("demo/Helper.java", """
        package demo;
        class Helper {

            static int answer() { return 42; }

        }
        """)
    .addInferredSource("""
        package demo;
        public class Main {

            public static void main(String[] args) {
                System.out.println(Helper.answer());
            }

        }
        """)
    .build();

TraceRequest request = TraceRequest.builder(bundle.source())
    .format(TraceFormat.MODERN)
    .build();

TraceOutcome outcome = Tracer.trace(request);
System.out.print(outcome.stdout());
```

`addSource(logicalPath, text)` accepts empty or syntactically invalid Java text;
it validates bundle structure rather than compiling the program.
`addInferredSource(text)` parses only to choose a package-relative filename: it
uses exactly one public top-level type, or the sole top-level type when none is
public. Ambiguous or unparseable input requires an explicit logical path.
Insertion order is retained. The tracer still selects the first source containing
a recognized main-method candidate, so ordering files can affect the entry point.

Logical paths must be relative `.java` paths. Backslashes normalize to forward
slashes; duplicate normalized paths are rejected rather than replaced. Absolute
paths, colons, empty or dot/traversal segments, control characters, line terminators,
and leading or trailing whitespace are rejected. Inference and explicit naming
share these path rules. Source and path strings must contain no unpaired UTF-16
surrogates, so UTF-8 export does not silently replace text. Delimiter-shaped source
lines are also rejected, even inside Java text blocks or block comments; the existing
format has no escape mechanism. Errors identify the input and one-based line number,
using the logical path when available.

Every file receives a `// --- path.java ---` delimiter, including a one-file bundle.
Source text, line endings, and any source BOM are preserved. A single LF is inserted
only when needed to place a following delimiter on a new line; that LF becomes part
of the preceding file's content. The final file is unchanged. Thus a bundle preserves
source contents except for this documented framing newline, and does not normalize
line endings or silently remove a BOM to make Java parsing succeed.

`addFile(logicalPath, Path)` and `addInferredFile(Path)` read and strictly decode
UTF-8 immediately when called, closing their file handles. They capture text rather
than retaining a live file: later file changes or deletion do not affect the builder
or a built bundle. The inferred form derives the logical name from source declarations,
not the host filename. From the repository root, inside a method that handles or
declares `IOException`, these are equivalent naming choices:

```java
SourceBundle explicit = SourceBundle.builder()
    .addFile("Main.java", java.nio.file.Path.of("examples/primitive-bits/Main.java"))
    .build();
SourceBundle inferred = SourceBundle.builder()
    .addInferredFile(java.nio.file.Path.of("examples/primitive-bits/Main.java"))
    .build();

try (java.io.InputStream input = explicit.openStream()) {
    String copiedSource = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assert copiedSource.equals(explicit.source());
}
try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(
        java.nio.file.Path.of("submission.txt"))) {
    inferred.writeUtf8(output);
}
```

Each `openStream()` call starts a fresh UTF-8 stream at its beginning; consuming or
closing one does not affect another. `writeUtf8(OutputStream)` writes the same UTF-8
representation without flushing or closing the caller's output and propagates I/O
failures. The caller closes the output in the example above.

Use `addFiles(root, glob)` to capture multiple files with logical paths relative to
`root`, or `addInferredFiles(root, glob)` to infer each logical path from its Java
declarations. Both return the builder. From the repository root, inside a method
that handles or declares `IOException`, the following bundles contain the same
packaged example and print `4 * 3 = 12`:

```java
java.nio.file.Path root = java.nio.file.Path.of("examples/example14");
SourceBundle explicit = SourceBundle.builder()
    .addFiles(root, "cs1302/**/*.java")
    .build();
SourceBundle inferred = SourceBundle.builder()
    .addInferredFiles(root, "cs1302/**/*.java")
    .build();
assert explicit.source().equals(inferred.source());

TraceOutcome outcome = Tracer.trace(TraceRequest.builder(explicit.source()).build());
System.out.print(outcome.stdout());
```

The glob always uses `/` separators and case-sensitive Unicode code-point matching,
independently of the host filesystem's conventions. The root is an ordinary host
`Path`. Patterns support `*` (zero or more characters) and `?` (one character)
within a path component, character classes such as `[A-Z]` and `[!0-9]`, non-nested
brace alternatives such as `{Main,Helper}.java`, and backslash escapes for
metacharacters. These component wildcards never cross `/`. A backslash in a Java
string literal must itself be escaped. `**` must occupy a whole path component;
it spans zero or more path components.
For example, `demo/**/*.java` selects both `demo/Main.java` and
`demo/nested/Helper.java`, while `demo/**` selects files at every depth below `demo`.
Use `demo/Foo*Bar.java`, not `demo/Foo**Bar.java`, for a wildcard within a filename.
Brace groups require at least two nonempty alternatives, which may contain `/`
and whole-component `**`. Every alternative must produce a relative pattern with
nonempty components other than `.` or `..`; leading and trailing `/` are rejected.
Backslash escapes are limited to `*`, `?`, `[`, `]`, `{`, `}`, `,`, `!`, `-`, and
backslash itself; an escaped `/` is invalid. Within a character class, a leading or
trailing `-` is literal, and ranges must be ascending. Empty patterns, unmatched
delimiters, invalid escapes, nested brace groups, and other malformed syntax are
rejected before filesystem traversal.

Matching includes hidden regular files and adds no implicit filename-extension
filter. In explicit mode every selected root-relative path must pass the same
logical `.java` path validation as `addSource`. In inferred mode a matching file
such as `Main.txt` can supply `demo/Main.java` if its captured Java declarations
yield that name. A selected file that fails naming or content validation fails the
call; it is not silently skipped. Zero matches throws `IllegalArgumentException`
identifying the root and glob. Malformed glob syntax also throws
`IllegalArgumentException`.

The builder appends each matching group after existing entries, sorting by the
encountered root-relative path with `/` separators, using case-sensitive,
locale-independent string order. Sorting uses the selected file's path even when
its inferred logical name differs. Distinct paths to the same physical file remain
separate matches: explicit names can preserve both aliases, while duplicate
inferred names reject the call. Existing entries and earlier matches both
participate in duplicate-path validation. Order matters when more than one file
contains a main-method candidate.

The two-argument overloads default `followLinks` to `false`: symbolic links found
within the root are skipped, including links to files, directories, and missing
targets. The caller-selected root itself is resolved once and may be a symbolic
link. Use `addFiles(root, glob, true)` or `addInferredFiles(root, glob, true)` to
follow discovered links, including links to targets outside the root. Matching,
ordering, and explicit logical names still use the encountered path beneath the
root, not the target's resolved path. With following enabled, an encountered
broken link or directory cycle aborts the call even if the glob would not select
that path. Traversal and file-read failures also abort the call. The root must
resolve to an existing directory; a missing, broken, or non-directory root and
other filesystem failures throw `IOException`.

Each bulk call captures and strictly decodes all selected UTF-8 files before it
returns. It changes the builder only after every selected file passes validation;
any failure leaves all prior entries unchanged. Later edits or deletion do not
change captured text. This is an atomic update to the builder, not a simultaneous
snapshot of the filesystem: files may change between traversal and reading.

Builders are mutable, reusable, and not thread-safe. `build()` requires at least one
file and creates an immutable bundle; later additions do not change earlier builds.
Rejected additions leave previously added files unchanged.
`toBuilder()` starts an independent builder containing the captured files in order,
without rereading their original paths. A bundle is self-contained as captured source
text, not a guarantee that Java compiles, a main method exists, or every dependency
has been supplied. Glob selection is explicit file collection; it does not discover
dependencies from imports or execute guest code.

## Build and customize values

Since 4.1.0, `TraceRequest`, `TraceLimits`, and `TraceOutcome` have nested builders
and `toBuilder()` copy methods. Existing record components and constructors remain
available. Builders are mutable, reusable, and not thread-safe; each build returns
a new value. Setters use the record component names and explicit boolean values.

`TraceRequest.builder(source)` starts from the convenience constructor's defaults.
The initial source must be non-null. Required scalar values supplied later are
validated by the record constructor at `build()`. The breakpoint setter immediately
copies its list and rejects null lists or entries, as the existing constructor does.
Parsing and execution validation still happen at their established lifecycle points.
For example, multithread settings are interpreted by trace-job resolution without
rewriting the requested inspection or chronological fields in the record.

`TraceLimits.builder()` starts from `instructorDefaults()`. To start with no
budgets, use `TraceLimits.unlimited().toBuilder()`. All limit names retain their
units, including `timeoutMillis` and `outputBytes`; zero means unlimited. Negative
limits and an excessively large timeout are rejected at `build()` by the same
validation as direct construction.
The timeout starts at the trace phase, excluding source preparation and reusable
guest startup; it is not a whole-job deadline. Thread and frame ceilings apply to
multithread capture. The element budget counts cumulative inspected items, including
frames, locals, and string storage units. Retained-byte accounting does not bound
process memory or final serialized JSON size. See [bounded tracing](../BOUNDED_TRACING.md#limits)
for the complete budget and accounting semantics.

```java
TraceLimits limits = TraceLimits.builder()
    .timeoutMillis(5_000)
    .snapshots(500)
    .build();
TraceRequest first = TraceRequest.builder(source).limits(limits).build();
TraceRequest second = first.toBuilder().format(TraceFormat.MODERN).build();
```

`TraceOutcome.builder()` is for integrations and fixtures that supply their own
detached values. It defaults counters and diagnostics to empty collections,
`complete` to false, and strings to null. Collection setters immediately copy and
reject null collections or entries, preserving the outcome constructor's contract.
Other fields retain the constructor's null acceptance; no status-combination or
JSON validation is added. Normally obtain a complete outcome from `Tracer.trace`.

The outcome builder stores `traceJson` and `json` verbatim. It does not generate,
parse, or synchronize JSON. An unchanged `outcome.toBuilder().build()` preserves
every value, but changing `stdout`, status, or another metadata field does not
update either string. Supply corresponding JSON explicitly when changing metadata.
`writeJson` writes the supplied envelope; an unset null envelope is not a usable
JSON document. Production tracing keeps metadata and JSON consistent through its
rendering module.

Collection detachment is shallow: builders copy immediate containers, not arbitrary
nested values. They do not change the ownership behavior of existing constructors.
The internal step and batch-wire builders have different null and ordering rules;
see [internal tracing modules](../guides/java-module-migration.md#building-internal-values).

## Discover breakpoints

`Tracer.discoverBreakpoints(source)` compiles submitted sources and
reads executable line tables without running guest code. The returned
`BreakpointDiscovery` contains immutable `BreakpointLocation` values, ordered by
source path and line, or preparation diagnostics. Locations use package-relative
paths and one-based lines. A preparation failure has diagnostics and no locations.
Temporary compilation resources are released before the operation returns.
Use `Tracer.discoverBreakpoints(source, dependencies)` for
[binary dependencies](#binary-dependencies). Dependency line tables are not
included, and this overload also releases selected dependency resources before returning.

## Process a batch stream

```java
import cs1302.tracer.api.BatchTracer;
import java.io.InputStream;
import java.io.Writer;

// requests and responses are caller-owned InputStream and Writer instances.
try (BatchTracer tracer = BatchTracer.builder()
        .workers(2)
        .maxJobsPerWorker(100)
        .maxInFlight(16)
        .build()) {
    tracer.processStream(requests, responses);
}
```

The builder requires all three numeric settings explicitly and defaults
`completionOrder` to false, for submission order. Missing settings cause
`IllegalStateException` at `build()`; nonpositive numbers cause
`IllegalArgumentException` before worker or scheduler allocation. Setters only
record configuration. Each successful build owns a fresh processor, independently
of earlier builds; closing one does not close another. There is no `toBuilder()`
for a live processor. The original four-argument constructor remains available.
The processor owns the worker pool and guest cleanup; call `close()` after stream processing or use
try-with-resources. Stream processing flushes responses but leaves caller-owned
streams open. Blank input lines are ignored, and successful processing returns after
EOF and all admitted responses. A processor can handle consecutive streams;
call `processStream` sequentially and never use a closed processor.
The [batch protocol](batch.md) preserves existing defaults and per-job result
formats; optional class-path selections belong to each request, allowing consecutive
jobs to use different dependencies. The CLI `batch-trace` command uses this same public operation.

## Migrate Java integrations from 3.x

See the [3.x to 4.x migration guide](../guides/java-api-migration.md). This section
remains as a destination for existing links.
