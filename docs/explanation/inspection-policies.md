# Inspection policies

An inspection policy decides how the tracer turns guest objects into heap entries
while it captures a snapshot. In particular, it decides whether the tracer may call
the traced program's methods to do so. `TRUSTED`, the default, calls a small set of
guest methods to present collections, maps, primitive wrappers, and buffered output
in their logical form. `FIELDS` calls none and shows what each object stores.

This page explains that trade-off and how it connects to tracing budgets, source
discovery, and multithread capture. For how heap entries relate to frames and
references, see [how a trace represents execution](trace-model.md). Option syntax
and exact representations live in the [CLI](../reference/cli.md#trace-defaults-at-a-glance),
[batch](../reference/batch.md), [Java](../reference/java-api.md#trace-a-submitted-program),
and [output](../reference/output.md#heap-fields-and-kinds) references.

## Logical views require guest calls

The debugger can read a suspended object's fields and an array's elements without
running guest code. That is enough for an ordinary object, an array, or a string.
It is not enough to show a collection as the values it contains. Those values are
defined by the collection's methods; how they are stored is a detail of its class.

Under `TRUSTED`, the tracer asks the objects. While capturing a snapshot, it can
invoke these guest methods:

- `toArray()` on any object that implements `java.util.Collection`, including a
  collection class the program defines.
- `entrySet()` on any `java.util.Map`, `toArray()` on the returned entry set, and
  `getKey()` and `getValue()` on each entry.
- The accessor of a **JDK** primitive wrapper, such as `intValue()` or `booleanValue()`.
- `hashCode()` on a program-defined enum constant whose lazily computed `hash`
  field is still zero, unless that evaluation is turned off.
- `getRGB()` on a `java.awt.Color`, only when the object has no stored `value`
  field to read.
- `flush()` on the objects currently installed as `System.out` and `System.err`,
  before each snapshot.

These are the only guest methods the tracer invokes during capture. The heap calls
run on the suspended event thread at the capture point.

In return, readers see logical views. Modern output presents lists and other
collections as `array` entries with their elements, maps as `object` entries whose
synthetic fields have type `entry`, and wrappers as `box` entries that hold the
primitive value. Python Tutor output uses `LIST` for lists, `SET` for other
collections, `DICT` for maps, and an `INSTANCE` with a single `value` field for
wrappers. Flushing lets buffered text, such as a prompt printed without a trailing
newline, appear in snapshots before a newline is printed. Examples 21, 29, and 30
in the repository illustrate prompt flushing and wrapper representations.

Sets, including `Collections.unmodifiableSet(...)`, use this collection path.
An unmodifiable wrapper retains its own identity and delegates inspection to its
backing collection; it does not make guest inspection side-effect-free. Captured
elements retain the returned order, references, and nulls. A guest exception or
null `toArray()` result falls back to raw fields for a program-defined object or
an opaque object for a JDK class. See [set objects](../reference/output.md#set-objects)
for view, ordering, and failure semantics.

## Guest calls become part of the traced execution

A guest method call runs code inside the traced program at a moment the program did
not choose. Whatever that code does becomes part of the execution being observed.

Lazy enum hash evaluation shows this directly. The tracer calls `hashCode()` so that
the captured `hash` field holds a computed value instead of zero, and the call
itself stores that value in the guest. The trace then shows a hash the program may
never have requested.

Methods the program defines can do more. An overriding `toArray`, `entrySet`, or
entry accessor, or the `flush` of a custom stream installed as `System.out`, can
mutate state or never return. For a student-written collection class, `TRUSTED`
inspection runs the student's code at every snapshot that reaches such an object.
Later snapshots, guest output, and the rest of the run can then reflect work the
tracer caused rather than work the program performed.

A call that throws does not fail the snapshot. If a collection or map accessor
throws, inspection falls back to the object's raw fields when the program defines
its class, or to an entry with no fields when it is a JDK class. Failures from
`getRGB`, `hashCode`, and `flush` are ignored.

## A blocked call ends at the tracing deadline

Inspection cannot distinguish a slow call from one that never returns. The tracing
deadline handles both. When execution enters the trace phase, an independent
watchdog starts enforcing the deadline. When it stops a job, it forcibly terminates
the guest process, which also ends a call blocked inside it. The job reports
`timeout`, the snapshot being captured is not retained, and a result envelope keeps
the completed snapshots. A reusable batch guest terminated this way is replaced
before its worker runs another job.

The watchdog can enforce only a deadline that exists. A timeout of zero means no
deadline, and omitted limits are unlimited for `trace --result-envelope` and for
batch requests. In those jobs, the deadline cannot end a `TRUSTED` call that never
returns. Ordinary CLI tracing and Java convenience requests use finite instructor
defaults, including a 10-second tracing timeout. The `InspectionPolicy.TRUSTED`
Javadoc recommends finite budgets because guest calls can block. The
[guest modes table](../BOUNDED_TRACING.md#guest-modes) lists each entry point's
budget profile, and the [stop-code table](../BOUNDED_TRACING.md#stop-codes) defines
`timeout` and `cancelled`.

## Field-only inspection reads stored state

Under `FIELDS`, the tracer reads every object other than arrays and strings through
its instance fields. Collections, maps, wrappers, and other JDK objects become
ordinary objects: `object` entries in modern output and `INSTANCE` values in Python
Tutor output. Arrays and strings keep their dedicated representations, and so do
colors. A color is read from its stored `value` field; if that field is absent, the
color value falls back to zero rather than calling `getRGB`. No stream is flushed,
so captured output contains only bytes the guest has already written to its output
pipes.

Consider these statements in `main`:

```java
List<String> names = new ArrayList<>();
names.add("Ada");
Integer boxed = 5;
```

With modern output, `TRUSTED` shows `names` as an `array` entry whose single element
refers to the string `"Ada"`, and `boxed` as a `box` entry holding `5`. Under
`FIELDS`, `names` is an `object` entry whose fields are the list's implementation
fields, such as `elementData`, `size`, and `modCount`. Its `elementData` field
refers to a backing array, which becomes a heap entry of its own. `boxed` is an
`object` entry that exposes the wrapper's stored field; the
[primitive metadata contract](../reference/output.md#optional-primitive-binary-metadata)
describes where wrapper metadata appears in that case.

Those field names come from the JDK's class implementations. They vary by JDK, and
`FIELDS` output is not promised to match the `TRUSTED` presentation. Envelope
results, Java outcomes, and batch results carry the diagnostic `FIELDS inspection uses raw fields for collections and wrappers; guest methods and stream flush are not invoked.`
Diagnostic wording is not a stable identifier.

The difference reaches beyond collections. `TRUSTED` exposes selected stored
components of supported `java.time` value objects without invoking guest methods.
Date/time components retain their references; zone-rule machinery and caches are
omitted from these logical views. `FIELDS` keeps raw traversal instead. See the
[date/time field reference](../reference/output.md#date-and-time-objects) for the
supported types, layout fallback, and budget implications.

`TRUSTED` shows other JDK platform
objects, such as a `Scanner` or a `StringBuilder`, as entries with no fields; an
enum constant keeps its name. `FIELDS` exposes and traverses their internal fields
as well. Each inspected field counts toward the per-snapshot element budget, and
each newly reached object counts toward the heap-object budget. A `FIELDS` snapshot
of the same program can therefore be larger and reach `element_limit` or
`heap_limit` sooner. Lambda entries reconstructed from source look the same under
both policies.

## Field-only inspection uses only submitted sources

Choosing `FIELDS` also changes source preparation: the job compiles only its
submitted sources. Under `TRUSTED`, CLI and batch jobs whose entry file declares a
package can also compile matching neighboring sources, located relative to an input
file or found beneath the process working directory, `src/main/java`, or `src`.
Those discovered sources can affect compilation without appearing in the result's
`sources` map, and the working directory can change what a stdin job compiles.

Under `FIELDS`, the submitted bundle is the program's complete source. Standard JDK
classes and explicitly configured binary dependencies remain available. The
[multiple-file recipe](../guides/tracing.md#submit-multiple-files) recommends this
combination to avoid compiling matching files by accident, and the
[CLI discovery reference](../reference/cli.md#source-input-and-discovery) defines
each boundary. One-shot Java `Tracer.trace` never discovers neighboring sources, so
for Java callers the policy does not change which sources are compiled.

## The CLI accepts field-only inspection only with an envelope

Ordinary CLI `trace` accepts `--inspection FIELDS` only together with
`--result-envelope`; otherwise it exits with status 2 and
`Inspection controls require --result-envelope`. An explicit `--inspection TRUSTED`
is accepted in either mode. Field-only inspection was introduced in release 2.9.0
with opt-in envelope jobs, and existing non-envelope invocations kept their output
contract.

The check applies to the requested policy. `--multithread --format modern`
therefore runs field-only inspection without an envelope: the requested policy
remains the default, and multithread capture selects `FIELDS` itself. The
[CLI option interactions](../reference/cli.md#option-interactions-and-validation)
list both rules.

## Multithread capture always reads fields

Multithread capture pauses all threads through JDI event-set suspension and records
their stacks together. A guest method call would run one thread while the tracer
observes the other threads' stacks, so multithread jobs never make one. They use
`FIELDS` even when a request selects `TRUSTED`: no stream flush, collection or map
accessor, wrapper accessor, or enum hash evaluation. A Java `TraceRequest` keeps the
requested value; only the effective policy changes.

Results with a `diagnostics` array add
`Multithread capture uses field-only inspection and application frames.` alongside
the field-only diagnostic. Because the effective policy is `FIELDS`, multithread
jobs also compile only submitted sources. See
[multithread capture](../BOUNDED_TRACING.md#multithread-capture) and
[opt-in thread capture](../reference/output.md#opt-in-thread-capture).

## Defaults differ by entry point

Every entry point defaults to `TRUSTED`, but not to the same budgets. Ordinary CLI
`trace` and Java `TraceRequest` convenience constructors and builders pair it with
finite instructor defaults. `trace --result-envelope`, `batch-trace`, and
`BatchTracer` leave omitted limits unlimited, so their default is `TRUSTED`
inspection with no tracing deadline.

Selecting `FIELDS` also works differently in each. The CLI requires
`--result-envelope`. A batch request sets the case-sensitive `inspection` token; an
unrecognized token, including lowercase `"fields"`, silently selects `TRUSTED`. The
full Java `TraceRequest` constructor requires an explicit policy, and the builder
accepts one.

Lazy enum hash evaluation is configurable only in the CLI, where
`--no-eval-enum-hash` turns it off. Java and batch jobs under `TRUSTED` always
evaluate lazy enum hashes; in those entry points, only field-only inspection,
including multithread capture, avoids that call.

## Choosing a policy

`TRUSTED` fits programs whose methods can be relied on to behave when called during
inspection, such as instructor examples, and lessons where logical views help:
lists as element sequences, maps as entries, wrappers as values, and prompts that
appear before a newline. It pays for those views with guest calls, so it works best
with a finite timeout. That matters most in envelope and batch jobs, whose omitted
limits are unlimited.

`FIELDS` fits jobs where the trace must not call into the program during capture:
hosted student submissions, programs that define their own collection, map, or
stream classes, and jobs that should compile only what was submitted. It pays with
implementation fields instead of logical views, field layouts that vary by JDK,
potentially larger heaps, and output limited to bytes already written. The
[troubleshooting guide](../guides/troubleshooting.md#the-trace-looks-different-from-what-you-expected)
covers collections whose shape differs under `FIELDS`.

## Neither policy isolates guest code

Both policies govern only what the tracer does while it observes. Guest code runs
unchanged under either one. `FIELDS` removes the tracer's own calls into the
program; it does not restrict anything the program itself does. Despite its name,
selecting `TRUSTED` does not make a program safe to run, and selecting `FIELDS`
does not make an untrusted program safe.

For hosted submissions, the [runner contract](../RUNNER_CONTRACT.md#job-interface)
requires a runner that isolates the whole job, including parsing and compilation,
before the tracer starts. It recommends `FIELDS` with explicit finite limits inside
that boundary for CLI, batch, and Java integrations alike. Those controls are
useful inside the boundary; they are not the boundary.
