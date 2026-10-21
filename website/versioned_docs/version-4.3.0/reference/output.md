# JSON output reference

For an annotated walkthrough, start with [Read a trace](../tutorials/read-a-trace.md).
This reference defines field behavior and complete output examples.

The tracer supports Python Tutor (`pytutor`, default) and modern (`modern`) JSON.

## Result contracts

The CLI's capture options determine which snapshots are retained and how they are
grouped. Choose the result shape before writing code that reads it. The table
describes successful `trace` commands; an error can leave ordinary stdout empty.

| CLI selection | Modern JSON (`--format modern`) | Python Tutor JSON (`--format pytutor`, default) |
| --- | --- | --- |
| No capture options | A root object with `steps`, containing the final main-exit snapshot on normal return. | A root object with `trace`, containing the final main-exit snapshot on normal return. |
| `--breakpoints 4` | A root object with `breakpoints`; `breakpoints["4"]` is one latest snapshot, or an array when more than one file has a retained hit at line 4. | A root dictionary; `["4"]` is one complete Python Tutor payload, or an array of payloads when more than one file has a retained hit at line 4. |
| `--breakpoints 4 --accumulate-breakpoints` | `breakpoints["4"]` is an array of snapshots in hit order. | `["4"]` is an array of complete Python Tutor payloads, one per hit. |
| `--all-breakpoints` | A root object with a chronological `steps` array. | A root object with a chronological `trace` array. |
| `--all-breakpoints --breakpoints 4` | A chronological `steps` array for the selected location, plus the main-exit snapshot when reached. | A chronological `trace` array for the selected location, plus the main-exit snapshot when reached. |
| `--result-envelope`, with any selection above | An envelope whose `trace` contains a modern payload with a `steps` sequence when available. | An envelope whose `trace` contains one Python Tutor payload with a `trace` sequence when available. |

Line `4` is an example, not a universal breakpoint. Discover executable locations
with `list-breakpoints`; a selected line that is not reached has no hit to display.
`--all-breakpoints` selects chronological presentation even when
`--accumulate-breakpoints` is also supplied. Without an explicit `--breakpoints`
selection, accumulation alone does not request all executable lines.
Selected-breakpoint dictionary keys are unqualified line numbers, even when the
request uses `FILE:LINE` selections. In latest mode, a shared line key retains one
latest hit per file; inspect each snapshot's `file` to distinguish them. With
accumulation, the array interleaves those files' hits in capture order for that
line. Consumers must accept a single value or an array in latest mode. Use
`--all-breakpoints` or `--result-envelope` for a uniform sequence instead.
See [uncaught exceptions](#uncaught-exceptions) for final-state capture when the
entry method does not return normally.

The envelope separates trace-generation status from the payload: it adds a
versioned status, effective limits, counters, diagnostics, and bounded guest
streams around the trace, and its `trace` can be `null` when unavailable. It also
changes the budget profile: **omitted CLI envelope limits are unlimited**, whereas
ordinary tracing has finite defaults. Java request convenience constructors use
finite defaults, and omitted batch limits are unlimited. Consult
[bounded tracing](../BOUNDED_TRACING.md) for the full limits and partial-result
contract and the [CLI exit-status lookup](cli.md#exit-statuses) before automating
these commands. CLI envelopes, Java API outcomes, and the `result` in each batch
response share that version 1 result schema. The [Java reference](java-api.md)
and [batch protocol](batch.md) define their entry-point defaults and transport
wrappers. Serialization of optional properties also differs between ordinary and
envelope output; see [null properties](#null-properties-in-modern-output).

For a runnable comparison of latest, accumulated, and chronological captures, use
[Trace a loop](../tutorials/trace-a-loop.md). For the relationship between captured
state and JSON presentation, read [How a trace represents execution](../explanation/trace-model.md).

## Complete chronological examples

These examples are complete output from the source below using `--all-breakpoints`.
CI compares them with actual output after normalizing heap IDs.

<!-- source:start -->
```java
public class Main {
    public static void main(String[] args) {
        int answer = 42;
        System.out.println(answer);
    }
}
```
<!-- source:end -->

## Shared source metadata

Both formats include `code`, `sources`, and `entryFile`. `code` retains the supplied
input; `sources` maps package-relative file paths to submitted source text, excluding
multi-file delimiters. `entryFile` selects the initial file and is a key in `sources`.
Map order does not choose the entry point. A step or frame's `file` can be used to
look up submitted text only when that key exists in `sources`. Single-stack
capture includes **JDK** and binary-library frames between submitted frames; their
debug paths, such as `java/util/Collections.java`, are not supplied source files.
Frames can also lack debug source information. Consumers must handle frames
without available source. The library frames and their line numbers depend on the
JDK version and build. Multithread capture filters stacks to submitted application
frames. Discovered dependencies outside the submitted bundle are not included in
`sources`.

Source-backed line numbers are 1-based. A multithread step whose triggering thread
has no submitted frame uses `line: 0`, `method: ""`, and an empty top-level
`callStack`; its `file` is absent in ordinary output and null in envelopes. Treat
line zero as no source location, rather than indexing the last line of a file.
Use `triggeringThreadId` and `threads` to interpret that lifecycle step.

Logical submitted paths choose compilation destinations. Escaping or duplicate
destinations are rejected during preparation. Rendered source identities instead
combine the declared package with the source basename. Distinct logical destinations
can therefore compile successfully yet produce the same debug source path; rendering
rejects that ambiguity when constructing source metadata. The same basename in
different packages remains distinct. Source text, line endings, ordering, and entry
selection are preserved when prepared tracing renders after compilation resources
close or retries rendering during recovery.

## Input and output observations

Root `stdin` is the input supplied to the guest, separate from source text read
through the tracer's stdin. Snapshot `stdinConsumed` is the observed consumed
prefix of that string, and `stdinOffset` is its zero-based UTF-16 code-unit length,
not a byte offset. Input observation follows logical reads by `java.util.Scanner`
(`next*`, `find*`, and `nextLine`), `java.io.BufferedReader.readLine`, and
`java.lang.IO.readln` on standard input; methods that do not complete one of
those reads, such as `BufferedReader.read` or `Scanner.skip`, do not move the
cursor. In a dedicated guest (CLI tracing and multithread jobs), other readers on
`System.in` are observed at the underlying stream, whose buffering can run ahead of
the program's logical reads; reads through a separately constructed
`BufferedInputStream` around `System.in` are not observed. In a reusable guest
(single-stack Java API and batch jobs), those reads, including `System.in.read()`
and `System.in.read(byte[], int, int)`, are not observed, so the cursor can lag
behind them; of the direct stream reads, only `System.in.read(byte[])` advances it. Observation avoids counting nested reads twice; it is not a record of every
operating-system read or every custom reader's buffering. Each job starts with a
fresh cursor. See [input as a consumption cursor](../explanation/trace-model.md#input-is-a-consumption-cursor).

Input observation is paused while trusted collection inspection invokes `toArray()`.
Reads made by that accessor are not recorded, including calls to the supported
reader methods above. They still consume guest input, so `stdinConsumed` and
`stdinOffset` can lag behind. Later observed reads do not reliably reconstruct the
skipped reads, especially when input values repeat. Reader observation resumes
after the accessor finishes. `FIELDS` avoids these collection accessor calls.

Snapshot `stdout` and `stderr` contain cumulative captured output. Chronological
snapshots record progress; after execution, the final snapshot receives trailing
output when budgets permit. Selected-breakpoint output refreshes earlier locations
differently by caller:

- Ordinary CLI output refreshes the last retained hit under each bare line-number
  key. If multiple files share that key, only its final hit receives trailing
  output; another file's latest hit can keep an earlier prefix.
- Java/batch payloads refresh the latest retained hit at each `(file, line)`,
  except after a `trace_limit` stop.
- CLI `trace --result-envelope` refreshes only the final retained snapshot overall.
  Earlier selected locations can retain their capture-time output prefixes.

These rules also apply when selected hits are accumulated; earlier accumulated
hits keep their captured prefixes. A result envelope carries the available guest
streams independently of snapshot projection, even when no usable snapshot
payload exists.

## Uncaught exceptions

Without multithread capture, both formats use `event: "step_line"` for ordinary
source stops, main-exit snapshots, and terminal exception snapshots. A terminal
snapshot at a submitted throw line can immediately follow a pre-statement snapshot
at that same line; retain both rather than deduplicating by source location.
The last snapshot receives the captured exception stack trace in guest `stderr`
when output budgets permit. An ordinary trace can emit this JSON and exit zero,
because its process status describes trace generation. If default final-state
capture has no usable terminal snapshot, such as when an exception is thrown in
JDK code or the guest exits before normal main return, ordinary tracing can fail
without JSON; chronological capture can retain earlier stops.

CLI `trace --result-envelope` records observed uncaught exceptions as
`status: "failed"`, `stopReason: "guest_exception"`, and `complete: false`, with
available partial snapshots and guest streams. Multithread modern output also
emits `event: "exception"`; other threads can continue until guest termination or
a stop. Consult the [shared stop-code table](../BOUNDED_TRACING.md#stop-codes) and
[CLI exit statuses](cli.md#exit-statuses).

Single-stack Java and batch execution uses a reusable guest and has a current
exception-recognition limitation: an uncaught exception thrown inside JDK or
precompiled dependency code can leave `status: "completed"`, `complete: true`,
and no `guest_exception` reason, with the stack trace only in guest `stderr`.
Exceptions thrown directly from submitted code are recognized. Completion
metadata therefore does not prove successful guest execution in that mode.
Stderr can also contain ordinary application messages; its presence or absence
is not a stable machine classifier. See [guest modes](../BOUNDED_TRACING.md#guest-modes)
and the [batch exception contract](batch.md#uncaught-student-exceptions).

## Floating-point special values

Non-finite Java values remain valid JSON. Modern output represents `NaN`, positive
infinity, and negative infinity as the strings `"NaN"`, `"Infinity"`, and
`"-Infinity"` for both `float` and `double`. Finite values remain JSON numbers.
Python Tutor retains its existing literal conventions: floats use
`["NUMBER-LITERAL", "value"]`, while non-finite doubles use
`["SPECIAL_FLOAT", "value"]`, with the same special-value spellings.

## Lambda implementation types

Reconstructed lambda implementations include a method signature and body. In both
JSON formats, `--type-style` applies to the signature's return and parameter types,
including types inside generic arguments and arrays. The default `fqn` style
preserves their qualification; `simple` removes dotted prefixes with the same
presentation rule used for other type labels. It does not resolve Java names or
change the lambda body's text.

For example, the generated signature `java.lang.String apply(java.lang.Integer x)`
becomes `String apply(Integer x)` with `--type-style simple`, while the body
`return "java.lang." + x;` is unchanged. Qualified calls, comments, string literals,
and other body text are not type labels. Python Tutor keeps the existing
`["JAVA_LAMBDA", implementation]` representation; modern output keeps the existing
lambda heap object's `sam` field. No fields or schemas are added.

The same behavior applies to the batch request's `typeStyle` field and the Java
API's `TypeStyle` setting. Consumers must regenerate cached traces to obtain the
updated signature formatting.

## Lambda heap object labels

A lambda returned by a binary dependency may have no source implementation to
reconstruct. Its JVM-generated class name is not a useful description of its
functional-interface type. When a declared reference type and the actual JDI
interface hierarchy establish that view, the heap object's type label uses the
interface followed by `λ`. For example, an object observed through a
`Comparator<Person>` reference displays `Comparator<Person>λ` with simple
type names. A visualizer may append its existing identity, producing
`Comparator<Person>λ@91`; every alias still refers to that same object.

The `λ` is a presentation marker, not a declared Java class name or evidence
that generic type arguments survive erasure. Functional views are kept separately
from concrete runtime type evidence and are finalized after reachable-heap
inspection, so a later field observation can supply the interface type. Marker
interfaces do not supply a functional view. Consistent parameterized evidence for
one interface takes precedence over its raw aliases; conflicting parameterizations
retain the raw interface, and incompatible interface views retain the generated
runtime label. Wildcard evidence is not replaced with invented concrete arguments.

This correction applies to hidden lambda objects represented as ordinary heap
objects. Source-reconstructed lambda implementations retain their existing body
representation. Ordinary named classes implementing a functional interface keep
their concrete class labels. Both JSON formats use their existing type fields;
regenerate cached traces and images to obtain the corrected labels.

## Generic heap type labels

Heap type labels combine source-declared reference types with generic declarations
from the prepared binary dependencies. For example, a reference declared as
`Queue<Person>` can describe a `LinkedQueue<Person>` object when its superclass
and interface declarations prove that relationship. Type arguments compose across
multiple hierarchy levels, including renamed and reordered parameters. Instance
fields use their declaring owner's bindings, so an inherited `Node<T>` field can
display `Node<Person>` even when its value is `null`. Hidden fields retain their
respective declaring owners' types.

Library source files and local-variable debug tables from `javac -g` are not
required for binary generic declarations. The tracer uses the class-file generic
signatures through the prepared dependency solver; it does not execute guest
methods to obtain type arguments. This reconstructs labels from available evidence
and does not change Java's type erasure. Fully concrete field declarations such
as `Node<String>` remain available even for a nongeneric owner. Missing or
unsupported declarations retain erased labels; a partially determined concrete
class uses `?` for undetermined arguments, and conflicting evidence does not
produce a parameterization. Wholly undetermined classes remain raw.

Both JSON formats keep their existing type fields and schemas. Cached traces and
images must be regenerated to obtain corrected labels.

## Generic method variable types

Stack variable labels describe source-declared types for the active invocation.
For example, `max(T[])` called with an `Integer[]` variable displays `Integer[]`
for its parameter and `Integer` for its `T` locals with `--type-style simple`.
An explicit `<Number>` invocation keeps `Number` labels even when the value is
an `Integer` or `null`. Arrays and nested parameterized local declarations use
the same substitutions. Heap objects retain their runtime identities; their labels
follow the generic and lambda heap-label rules above.

Resolution requires source metadata and an unambiguous caller invocation matched
by declaring type and erased method descriptor. Explicit type arguments and
single-argument inference from declared variables, fields, casts, or array
accesses into a `T` or `T[]` parameter are supported. Substitutions require
concrete reference types (including default-package classes, parameterized types,
and arrays with reference or primitive components). Method type parameters shadow
class parameters, and recursive calls and threads have independent binding contexts.

A method with exactly one unbounded type parameter
and one parameter of that type, such as `<T> void helper(T item)`, also supports
string literals, explicit object creation, and non-generic method results when
the invocation is a standalone expression statement (no target type). For
example, `helper("hello")` labels `T` locals as `String`, while `helper(number())`
labels them as `Number` when `number()` declares that return type, even if it
returns an `Integer`. This additional subset excludes boxing, raw types, diamond
or anonymous construction, local classes, generic method results, receiver-dependent return
types, raw receiver ancestry, implicit instance receivers (including the outer
callee's receiver), array-clone expressions,
and types nested in generic owners. Nested arguments and receivers must
also have supported independent expression forms; no guest methods are executed
to infer a type. Bounded type parameters and `T[]` formals retain the existing
variable/field/cast/array-access inference rules.

Missing source, multiple matching calls on one line, target-dependent inference,
varargs, multi-argument inference, and unsupported capture or intersection types
retain unresolved type variables. The tracer does not infer method bindings from
runtime object classes. Both JSON formats use their existing type fields;
consumers need no rendering changes, but must regenerate cached traces and images
with the updated tracer.

## Modern format

These tables describe the modern payload, which appears at the JSON root for
ordinary CLI output and inside the envelope's `trace` for CLI envelopes, Java,
and batch. [Result contracts](#result-contracts) determine whether that payload
contains a sequence or a selected-breakpoint mapping. Unset fields follow the
[null-property rules](#null-properties-in-modern-output); optional does not mean
that key presence alone identifies a mode.

### Root fields

| Field | JSON value | Meaning |
| --- | --- | --- |
| `code` | String | Original submitted text, including multi-file delimiters when supplied. |
| `format` | String | Always `"modern"` for this payload. |
| `stdin` | String | Guest input supplied for the job; empty when omitted. |
| `steps` | Array of steps | Available for final-state and chronological output, and for every available envelope payload. Null or absent for ordinary selected-breakpoint output. |
| `breakpoints` | Object keyed by line number | Ordinary selected-breakpoint output: each value is a step or an array of steps, as defined in [result contracts](#result-contracts). Null or absent for sequence output. |
| `sources` | Object mapping source paths to strings | Submitted source text under package-relative debug identities; see [shared source metadata](#shared-source-metadata). |
| `entryFile` | String | Key in `sources` identifying the selected entry file. |

### Step fields

| Field | JSON value | Meaning |
| --- | --- | --- |
| `step` | Integer | One-based position in its emitted sequence. Numbering restarts within each selected-breakpoint bucket; it is not an instruction counter. |
| `line` | Integer | Source line of the top captured frame, or zero when `callStack` is empty. |
| `file` | String, null, or absent | Debug source path for the event location. A path need not be a key in `sources`; see [source lookup](#shared-source-metadata). |
| `event` | String | `step_line` for single-stack snapshots, including main exit and terminal exceptions. Multithread capture adds the [thread event labels](#opt-in-thread-capture). |
| `method` | String | Top captured frame's method name, or an empty string when there is no captured frame. |
| `callStack` | Array of frames | Captured event-thread stack, bottommost frame first and topmost last. |
| `statics` | Array of variables | Captured static fields; this is not every static field in the JVM. |
| `heap` | Object keyed by identity string | Captured reachable objects. A reference `{"ref": 49}` selects `heap["49"]` in that step; IDs are opaque and not stable across runs. |
| `stdout`, `stderr` | Strings | Captured guest streams, with [mode-specific cumulative output](#input-and-output-observations). |
| `stdinConsumed` | String | Observed consumed prefix of guest input, subject to the [reader-observation boundary](#input-and-output-observations). |
| `stdinOffset` | Integer | Zero-based UTF-16 code-unit length of that observed prefix. |
| `threads` | Array of thread records, null, or absent | Multithread records described [below](#opt-in-thread-capture); unset in single-stack capture. |
| `triggeringThreadId` | Integer, null, or absent | Identity of the event thread in multithread capture; unset in single-stack capture. |

### Frame and variable fields

Frames in both `callStack` and `threads[].callStack` use these fields:

| Frame field | JSON value | Meaning |
| --- | --- | --- |
| `methodName` | String | Method name for that captured invocation. |
| `line` | Integer | Debug source line for this frame; debug information can be unavailable. |
| `file` | String, null, or absent | Frame's debug source path, which can lack corresponding submitted text. |
| `isHighlighted` | Boolean | True for the topmost captured frame in that stack. |
| `this` | Reference, null, or absent | Receiver object for an instance invocation when captured. Static frames have no receiver; `--remove-method-this` suppresses it. |
| `locals` | Array of variables | Locals visible to the debugger at this stop. A variable not yet in scope is not an entry; `--remove-main-args` can also omit the entry frame's `args` local. |

Locals, static fields, and ordinary instance fields share one variable record:

| Variable field | JSON value | Meaning |
| --- | --- | --- |
| `name` | String | Variable or field identifier; specialized map entries use a synthetic key label. |
| `type` | String | Captured declared or inferred type label, formatted by `--type-style`; specialized map entries use `"entry"`. |
| `value` | Number, boolean, string, reference, or null/absent | Primitive value, `{"ref": id}`, or a null reference. Java `char` uses a string containing one UTF-16 code unit; [non-finite floats](#floating-point-special-values) use strings. A null variable remains an entry even when ordinary CLI JSON omits its `value` property. |
| `final` | Boolean | Captured declaration's finality metadata; false for synthetic map entries. It does not mean that a referenced object is immutable. |
| `binary` | Object or absent | Optional descriptor for a direct primitive value when binary metadata is enabled; see [modern placement](#modern-placement). |

The presence of the variable entry distinguishes a visible null reference from a
missing local. For a null reference, ordinary modern CLI JSON omits `value`, while
envelope, Java, and batch JSON include `"value": null`. Null elements in an array's
`elements` remain JSON null in every mode; omission applies to object properties.

### Heap fields and kinds

Every heap entry has `id`, `type`, and `kind`. The kind selects its payload fields;
unrelated fields are null or absent according to the output mode.

| Heap field | JSON value | Meaning |
| --- | --- | --- |
| `id` | Integer | Object identity, matching the string key in `heap` and numerical `ref` values. |
| `type` | String | Captured object type label after type-style formatting; lambda entries use `"lambda"`. Enum object labels can include the constant name. |
| `kind` | String | One of the six kinds below; inspection policy can change the selected representation. |
| `fields` | Array of variables, null, or absent | Instance fields or specialized map entries for `object`. |
| `elements` | Array of values, null, or absent | Ordered captured elements for `array`, using the same literal/reference/null encoding as variable values. |
| `value` | Scalar, null, or absent | String contents, a boxed primitive, or a color string, depending on `kind`. |
| `sam` | String, null, or absent | Detected single-abstract-method implementation description for `lambda`. |
| `binary` | Object or absent | Optional descriptor for a specialized boxed primitive. |
| `elementBinary` | Array or absent | Optional descriptors aligned with `elements`; nonprimitive positions are null. |

| `kind` | Payload | Representation |
| --- | --- | --- |
| `object` | `fields` | Ordinary object fields; specialized trusted maps instead expose synthetic entries. Enum labels can include their constant name. |
| `array` | `elements` | Java arrays and trusted logical collection representations. `type` distinguishes their captured types. |
| `string` | `value` | String contents. Modern output keeps string references and heap entries even with `--inline-strings`. |
| `lambda` | `sam` | Lambda implementation description, with `type: "lambda"`. |
| `box` | `value` | Specialized boxed primitive using the corresponding primitive JSON encoding. |
| `color` | `value` | Color text: `#RRGGBB` when opaque, otherwise `#RRGGBBAA`, with alpha last. |

Trusted map entries currently put `String.valueOf` of the rendered key in `name`,
`"entry"` in `type`, and the map value in `value`. For a reference key, the label
can look like `Reference[ref=49]`; it is not a structured key/reference field.
Do not parse it as though it were the `{"ref": 49}` value encoding.

Under `FIELDS` inspection, collections and wrappers are ordinary objects whose
captured fields can differ from these trusted logical representations. Arrays,
strings, and colors keep their dedicated representations. See
[inspection policies](../BOUNDED_TRACING.md#inspection-policies) and the existing
[primitive metadata contract](#optional-primitive-binary-metadata) for those
separate choices; enabling metadata does not change the kind or original value.

### Set objects

Under `TRUSTED` inspection, objects implementing `java.util.Set` use the existing
collection representation: modern `kind: "array"` with the captured concrete
type and `elements`, or Python Tutor `SET`. Recognition follows implemented
interfaces, including inherited interfaces and classes loaded from binary
dependencies. Lists still use Python Tutor `LIST`; other non-list collections
retain their existing `SET` representation.

Capture invokes the zero-argument `toArray()` and preserves its returned order,
nulls, and element references. It neither sorts nor deduplicates the result.
An unordered set's displayed positions do not imply a stable iteration order.
Generic type evidence and type-style formatting follow the usual type rules.

This includes `Collections.unmodifiableSet(...)` objects, whose binary runtime
class is `java.util.Collections$UnmodifiableSet`. The wrapper retains its own
identity and type; capture does not substitute its backing set. Changes through
the backing set appear in later snapshots, while retained snapshots preserve
their captured membership and element state. Unmodifiable does not mean immutable.
The inherited `toArray()` delegates to the backing collection and can execute
custom guest code. Checked, synchronized, sorted, navigable, and map-backed views
likewise use the collection path; inspection supplies no additional atomicity
guarantee for concurrent collections.

If `toArray()` throws a guest exception or incorrectly returns null, capture
falls back to raw fields for a program-defined object or an opaque object for a
JDK class. The fallback applies to the object being inspected: a failed
unmodifiable wrapper remains an opaque wrapper. Debugger and budget failures
retain their existing failure/stop behavior. This fallback also applies to lists
and other collections.

`FIELDS` and multithread capture use raw fields, including wrapper backing
references, without calling `toArray()`. Element limits apply after the guest
array has been returned; they do not bound its allocation. See the runnable
[set example](https://github.com/cs1302uga/cs1302-tracer/blob/main/examples/sets/Main.java)
and [inspection policies](../explanation/inspection-policies.md).

### Date and time objects

Under `TRUSTED` inspection, the following `java.time` objects expose selected
stored components. Modern output uses ordinary `object` entries with typed
fields; Python Tutor uses `INSTANCE` entries. No new heap kind or tuple tag is
introduced, and inspection invokes no date/time guest methods.

| Runtime type | Captured fields, in order |
| --- | --- |
| `LocalDate` | `year` (`int`), `month`, `day` (`short`) |
| `LocalTime` | `hour`, `minute`, `second` (`byte`), `nano` (`int`) |
| `LocalDateTime` | `date` → `LocalDate`, `time` → `LocalTime` |
| `Duration`, `Instant` | `seconds` (`long`), `nanos` (`int`) |
| `Period` | `years`, `months`, `days` (`int`) |
| `Year` | `year` (`int`) |
| `YearMonth` | `year`, `month` (`int`) |
| `MonthDay` | `month`, `day` (`int`) |
| `OffsetTime` | `time` → `LocalTime`, `offset` → `ZoneOffset` |
| `OffsetDateTime` | `dateTime` → `LocalDateTime`, `offset` → `ZoneOffset` |
| `ZonedDateTime` | `dateTime` → `LocalDateTime`, `offset` → `ZoneOffset`, `zone` → `ZoneId` |
| `ZoneOffset` | `totalSeconds` (`int`) |
| `ZoneRegion` (the JDK region-based `ZoneId` implementation) | `id` → `String` |

Names above are in `java.time`. Runtime type labels remain concrete, so a
region-based zone is labeled `java.time.ZoneRegion`, subject to `typeStyle`.
`Month` and `DayOfWeek` retain their existing enum representation. `Clock`,
formatters, chronology implementations, temporal strategies, and zone rules do
not acquire specialized views.

Components retain their stored Java types and final-field metadata. References
retain guest identity, including shared date/time components and zone-ID strings.
Equal but distinct objects remain distinct; aliases share one heap entry. String
inlining and optional primitive binary metadata follow their existing rules.
A `Duration` of negative one nanosecond has `seconds: -1` and `nanos: 999999999`.
The tracer neither converts durations to floating-point seconds nor combines them
into a potentially overflowing total-nanoseconds value. `Period` remains a
calendar amount expressed in years, months, and days. A zoned date-time retains
its stored local date-time, resolved offset, and zone ID without recalculating
zone rules on the host.

The recognized layouts are checked by field name, signature, and instance-field
status before values are read. An unknown layout falls back to the existing
opaque object entry, with no selected fields. This compatibility fallback does
not suppress cancellation, budget exhaustion, or guest-disconnection failures.
Selected fields and reached objects use the ordinary per-snapshot budgets;
these previously opaque objects can now require more elements and heap entries.

`FIELDS` inspection, including multithread capture, continues to traverse raw
instance fields. It can expose additional implementation details such as zone
rules and cached identifiers. See the [date/time recipe](../guides/tracing.md#date-and-time-values)
for a runnable duration and date/time example.

### Opt-in thread capture

`--multithread --format modern` adds `threads` and `triggeringThreadId` to every
step, including steps where only one application thread exists. It implies
chronological capture; `--breakpoints` can still restrict source-line stops.
Without `--multithread`, thread data is absent or null according to the
[serialization rules](#null-properties-in-modern-output). Python Tutor cannot
represent this mode; the CLI, batch validation, and Python Tutor serializer reject
it explicitly.

Each `threads` entry has the following fields:

| Field | JSON value | Meaning |
| --- | --- | --- |
| `id` | Integer | Guest thread identity within the job, unrelated to its name. |
| `name` | String | Observed thread name. |
| `state` | String | Observed JDI execution state listed below. |
| `callStack` | Array of frames | Captured application frames, using the [frame fields](#frame-and-variable-fields). |

IDs are guest object identities, stable within one job and unrelated to a thread's name.
Stacks contain submitted application frames, bottommost first. The top-level
`callStack` remains the triggering thread's application stack. All stacks share
one `heap` and one set of `statics`; the same reference ID means the same object.
A thread waiting inside JDK code can have an empty application stack.

Events are `step_line`, `main_exit`, `exception`, `thread_start`, and
`thread_death`. Death events include the terminating thread with `TERMINATED`
state and an empty stack; later steps omit it. Other JDI states are `NEW`,
`RUNNABLE`, `SLEEPING`, `BLOCKED`, `WAITING`, and `UNKNOWN`. They describe guest
execution state, independently of debugger suspension; `WAITING` does not
separate timed and untimed parking. JVM shutdown need not produce a death event
for every daemon thread.

Capture includes the entry thread's group and descendant groups, plus threads
observed executing submitted code. JVM service threads outside those groups are
excluded. The launcher's `DestroyJavaVM` thread can belong to the admitted group
and produce lifecycle steps even when the submitted program creates no threads;
it is not a thread created by that program. Initial `thread_start` and other
lifecycle steps can have [no source location](#shared-source-metadata), with line
zero and an empty event stack. Platform threads and conventional executor pools
are supported. Virtual-thread start
stops capture with `unsupported_virtual_thread`, preserving prior complete
snapshots in envelope output.

All threads pause during capture using
[JDI event-set suspension](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jdi/com/sun/jdi/event/EventSet.html),
and the tracer invokes no guest methods while inspecting them. JDK thread bookkeeping fields are omitted from heap objects;
fields declared by submitted `Thread` subclasses remain visible. Other ordinary objects
use field-only inspection; arrays, strings, and colors keep their dedicated
representations without guest method calls. These observations are one debugger-influenced run,
not deterministic scheduling or a record of every memory operation. Stream
output is process-wide and is not attributed to individual threads.

Tracing continues after `main` returns until non-daemon application threads
finish or a job limit is reached. Daemon threads do not keep the guest alive.
An uncaught worker exception produces an event and is retained as
`guest_exception` in the result envelope while other threads continue.
Use `--result-envelope` and explicit limits to retain partial traces and status.
Examples 34–41 cover these behaviors. Java callers set `multithread` to `true`
and `format` to `TraceFormat.MODERN` in `TraceRequest`, then call `Tracer.trace`.
The execution implementation selects a dedicated guest and field-only inspection;
batch requests use the same effective policy. Internal breakpoint-map tracing and
the reusable single-stack guest do not implement multithread capture.
Lock ownership and deadlock diagnosis are
not included; timeout alone does not prove deadlock.

<!-- modern:start -->
```json
{
  "code": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n",
  "format": "modern",
  "stdin": "",
  "steps": [
    {
      "step": 1,
      "line": 3,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 3,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "step": 2,
      "line": 4,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 4,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            },
            {
              "name": "answer",
              "type": "int",
              "value": 42,
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "step": 3,
      "line": 5,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 5,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            },
            {
              "name": "answer",
              "type": "int",
              "value": 42,
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "42\n",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    }
  ],
  "sources": {
    "Main.java": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n"
  },
  "entryFile": "Main.java"
}
```
<!-- modern:end -->

## Python Tutor format

`trace` contains snapshots with `stack_to_render`, `heap`, and `stdout`.
References use `["REF", id]`; heap objects use tagged tuple arrays. Additional
metadata such as `locals_attrs` and `heap_attrs` preserves Java type information.

A visible null reference local has an explicit JSON null in `encoded_locals`,
such as `"label": null`. Its name remains in `ordered_varnames`, with its type and
finality metadata in `locals_attrs`. Python Tutor serialization preserves these
null entries in ordinary and envelope output alike; a local absent from the frame
has no `encoded_locals` entry.

<!-- pytutor:start -->
```json
{
  "code": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n",
  "stdin": "",
  "trace": [
    {
      "stdout": "",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 3,
      "stack_to_render": [
        {
          "func_name": "main:3",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ]
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            }
          },
          "ordered_varnames": [
            "args"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "stdout": "",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 4,
      "stack_to_render": [
        {
          "func_name": "main:4",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ],
            "answer": 42
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            },
            "answer": {
              "final": false,
              "type": "int"
            }
          },
          "ordered_varnames": [
            "args",
            "answer"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "stdout": "42\n",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 5,
      "stack_to_render": [
        {
          "func_name": "main:5",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ],
            "answer": 42
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            },
            "answer": {
              "final": false,
              "type": "int"
            }
          },
          "ordered_varnames": [
            "args",
            "answer"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    }
  ],
  "userlog": "",
  "sources": {
    "Main.java": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n"
  },
  "entryFile": "Main.java"
}
```
<!-- pytutor:end -->

## Optional primitive binary metadata

Use `trace --include-primitive-bits`, batch request `"includePrimitiveBits": true`,
or the Java overload `Tracer.trace(request, true)`.
The default is false. When disabled, all binary metadata properties are omitted
and the existing literal values and output shapes are unchanged. The option works
with both formats, final-state, selected-breakpoint, and chronological capture,
ordinary output and envelopes. Multithread capture still requires modern output.

A runnable demonstration is in `examples/primitive-bits/Main.java`.

Each descriptor contains `type`, `encoding`, and `bits`. Bits are a string of
binary digits, most-significant first, with leading zeros, no separators, and no
`0b` prefix. Width and interpretation are:

| Java type | Width | `encoding` |
| --- | --- | --- |
| `byte`, `short`, `int`, `long` | 8, 16, 32, 64 respectively | `twos-complement` |
| `char` | 16 | `utf16-code-unit` |
| `float` | 32 | `ieee754-binary32` |
| `double` | 64 | `ieee754-binary64` |
| `boolean` | 1 logical digit | `logical` |

Boolean false is `"0"`; true is `"1"`. This logical encoding does not specify JVM
storage size. Character bits describe one UTF-16 code unit, including isolated
surrogates. Binary metadata describes the value captured through JDI, not its
original source spelling, address, or physical memory layout; it has no machine
byte order. Integer literals `42`, `0x2a`, and `0b101010` yield the same int bits.

Floating-point encodings distinguish positive and negative zero and represent
subnormal values, infinities, and NaNs. The encoder uses raw-bit conversions on
the received Java value; it does not deliberately canonicalize NaNs. Guest/JDI
transport and signaling-NaN behavior can affect NaN payloads, so this is not a
promise to recover every original guest NaN bit pattern. Existing human-readable
non-finite-value conventions remain unchanged.

### Encoding definitions and references

**Normative references** below define the Java values, standard encoding formats,
and conversion contracts used by this metadata. This output reference defines
the tracer-specific contract: property names, `encoding` identifiers, fixed-width
MSB-first strings, leading zeros, and the boolean mapping. **Informative references**
provide explanations and examples; they do not add requirements to that contract.
Java specification and API links use Java SE 21, the project's minimum supported
JDK. IEEE and Unicode references identify explicit editions.

#### Signed integers: `twos-complement`

**Normative:** [JLS §4.2, Primitive Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2)
defines the signed two's-complement representations of `byte`, `short`, `int`,
and `long`; [§4.2.1, Integral Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.1)
gives their ranges. The descriptor retains the type's own width from the table
above, including for negative `byte` and `short` values.

**Informative:** Oracle's [Primitive Data Types tutorial](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
introduces these integer types and ranges. It is a JDK 8-era tutorial; the Java
SE 21 specification above supplies the normative definitions here.

#### Characters: `utf16-code-unit`

**Normative:** [JLS §4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2)
defines `char` as an unsigned 16-bit value representing a UTF-16 code unit;
[§3.1, Unicode](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.1)
distinguishes code units from code points. [The Unicode Standard, Version 15.0,
§3.9, Unicode Encoding Forms](https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf)
defines code units (D77) and UTF-16 (D91). A descriptor records one code unit,
so an isolated surrogate is representable; it does not assert that this unit
alone is a Unicode scalar value or a well-formed UTF-16 sequence.

**Informative:** The Unicode Consortium's [UTF-8, UTF-16, UTF-32 & BOM FAQ](https://www.unicode.org/faq/utf_bom.html)
explains code units, surrogate pairs, and byte-order conventions. The descriptor
contains textual binary digits, not a UTF-16 byte stream.

#### Floating point: `ieee754-binary32` and `ieee754-binary64`

**Normative:** [JLS §4.2.3, Floating-Point Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3)
maps `float` and `double` to binary32 and binary64 and identifies
[IEEE Std 754-2019, Standard for Floating-Point Arithmetic](https://standards.ieee.org/ieee/754/6210/)
as the applicable edition. The Java API contracts for
[`Float.floatToRawIntBits`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Float.html#floatToRawIntBits(float))
and [`Double.doubleToRawLongBits`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html#doubleToRawLongBits(double))
specify the bit layouts and raw conversions used by the encoder. Their inverse
methods, [`Float.intBitsToFloat`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Float.html#intBitsToFloat(int))
and [`Double.longBitsToDouble`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html#longBitsToDouble(long)),
document signaling-NaN limitations. These contracts apply to the received Java
value; the guest/JDI NaN-payload limitation above still applies.

**Informative:** Oracle's [Numerical Computation Guide, Chapter 2: IEEE Arithmetic](https://docs.oracle.com/cd/E19059-01/stud.10/819-0499/ncg_math.html),
especially §§2.2.2–2.2.3, illustrates the sign, exponent, and fraction fields and
special values. This historical guide uses the older names *single* and *double*
and discusses machine storage; use the normative references above for the Java
contract. Its machine byte-order examples do not describe this textual metadata.

#### Booleans: `logical`

**Normative:** [JLS §4.2.5, The `boolean` Type and boolean Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.5)
defines the two Java values, `false` and `true`. The tracer defines their respective
`bits` strings as `"0"` and `"1"`; that mapping is a protocol convention of this
project, not an externally standardized JVM storage format.

**Informative:** The boolean discussion in Oracle's [Primitive Data Types tutorial](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
distinguishes two-valued logical information from a specified storage size.

### Modern placement

Locals, static variables, and instance fields have an optional sibling `binary`
property. For example, a captured byte value of -1 is represented as:

```json
{
  "name": "delta",
  "type": "byte",
  "value": -1,
  "final": false,
  "binary": {"type": "byte", "encoding": "twos-complement", "bits": "11111111"}
}
```

A `kind: "box"` heap object has `binary` alongside its existing `value`.
A heap object rendered with `elements` has an `elementBinary` array of equal
length, including an empty array for no elements. A position containing a direct
primitive has its descriptor; other positions contain JSON null. References
still refer to heap objects, where boxed payload metadata appears. Ordinary
objects carry primitive descriptors on their fields. Under field-only inspection,
wrappers remain ordinary objects and their captured primitive fields carry the
metadata; the option does not change inspection policy or invoke guest methods.

### Python Tutor placement

Existing scalar values and tagged tuples are preserved. Primitive locals and
statics add `binary` inside their `locals_attrs` or `globals_attrs` entry.
For example, `locals_attrs.delta` can be:

```json
{
  "type": "byte",
  "final": false,
  "binary": {"type": "byte", "encoding": "twos-complement", "bits": "11111111"}
}
```

`heap_attrs[id].binary` describes a specialized boxed primitive payload.
`heap_attrs[id].elementBinary` aligns with list/collection payload elements,
excluding the leading tuple tag. `heap_attrs[id].fieldBinary` aligns with ordinary
object field pairs, excluding the `INSTANCE` tag and type label. Both arrays use
null for nonprimitive positions; positional field metadata preserves duplicate
field names. Empty payloads have empty metadata arrays. Reference values themselves
never receive primitive metadata. Map keys and values captured as wrapper references
are described on their referenced heap objects.

Binary strings preserve all integer bits even in consumers whose JSON-number
handling cannot represent a 64-bit long exactly. Consumers can derive hexadecimal
formatting, digit groups, or sign/exponent/fraction highlighting from the descriptor.

### Cost and limits

Metadata is derived from owned typed snapshots during serialization. It is not
stored in retained snapshots, so it does not change `traceBytes` or `snapshotBytes`
accounting or capture-limit admission. It increases serialized payload size and
serialization allocations. Those counters do not measure final response bytes;
`outputBytes` limits guest stdout/stderr, not trace JSON. See
[bounded tracing](../BOUNDED_TRACING.md) for the accounting boundary.
Completed partial snapshots receive metadata just like complete traces.

## Selected breakpoints and envelopes

The [result contracts](#result-contracts) table defines every selected-breakpoint,
chronological, and envelope root shape, and the notes below it define envelope
budgets and `null` traces.

### Null properties in modern output

Ordinary modern CLI JSON omits properties whose values are unset. Modern payloads
inside CLI envelopes, batch results, and Java outcome JSON preserve explicit nulls.
Examples include root `breakpoints`; step `threads`, `triggeringThreadId`, or
`file`; frame `this`; heap `fields`, `elements`, `value`, and `sam`; and a variable's
`value` when the guest value is null. Unset primitive `binary` and `elementBinary`
metadata remains omitted in every mode.

Treat an absent optional property and a null value as equivalent. Detect a
selected-breakpoint mapping or multithread data by its non-null value and expected
type, rather than by key presence alone. The complete chronological examples above
show ordinary CLI output with its omission policy.
