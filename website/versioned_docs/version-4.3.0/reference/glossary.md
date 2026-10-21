# Glossary

This glossary defines terms used across the user guides and references. Each entry
links to the page that defines the term precisely. Implementation and ownership
terms for people changing the tracer are in the separate
[contributor glossary](https://github.com/cs1302uga/cs1302-tracer/blob/main/GLOSSARY.md).

## Binary dependency

A precompiled class directory or JAR that a job's program compiles and runs
against. Each job selects its own with `--class-path` (`-cp`), the batch
`classPath` field, or a Java `TraceClassPath`; the host `CLASSPATH` variable is
not used. Binary dependencies are unrelated to the tracer's own
[Maven dependencies](dependencies.md). See
[add binary dependencies](../guides/tracing.md#add-binary-dependencies).

## Breakpoint

A source location where the tracer can capture a [snapshot](#snapshot), written as
a line number such as `12`, a file-qualified line such as `demo/Helper.java:5`, or
`-1` for main exit. Only executable lines can be hit; `list-breakpoints` and
`Tracer.discoverBreakpoints` list them without running the program. Unlike a
debugger breakpoint, it never waits for a person: the tracer captures the state
and the program continues. See
[breakpoint listing output](cli.md#breakpoint-listing-output).

## Budget

A per-job cap on tracing work, also called a limit. The eleven budgets cover the
trace-phase time, the number and size of snapshots, retained guest output, heap
size, submitted source, and, in [multithread capture](#multithread-capture),
threads and frames. Reaching a cap is allowed; exceeding it stops the job with a
[stop reason](#stop-reason) such as `snapshot_limit`. Zero turns a budget off.
Omitted budgets are finite for [ordinary output](#ordinary-output) and Java
`TraceRequest` values, but unlimited for CLI envelopes and batch requests. Budgets
bound tracing work, not the whole job. See [limits](../BOUNDED_TRACING.md#limits).

## Calling process

The process that invoked the tracer: the CLI's JVM for `trace`, the `batch-trace`
JVM, or an application that uses the Java API. It reads, parses, and compiles the
submitted source, then launches and observes the [guest](#guest). See
[a job spans the calling process and a guest JVM](../explanation/trust-model.md#a-job-spans-the-calling-process-and-a-guest-jvm).

## Capture mode

The documentation uses "capture" for two independent choices. One is which states
a job keeps: the [main-exit snapshot](#main-exit-snapshot),
[selected-breakpoint capture](#selected-breakpoint-capture), or
[chronological capture](#chronological-capture). The other is how much of the
program each snapshot records: [single-stack](#single-stack-capture) or
[multithread capture](#multithread-capture). See
[selection, retention, and ordering](../explanation/trace-model.md#selection-retention-and-ordering-answer-different-questions).

## Chronological capture

Capture that keeps snapshots in execution order, selected with `--all-breakpoints`
(`-a`), batch `allBreakpoints`, or Java `chronological(true)`. With no breakpoint
selection it stops at every executable line. With a selection, its source-line
stops are limited to the selected lines. It also keeps the main-exit snapshot when
`main` returns, and it can keep a terminal snapshot for an uncaught exception
thrown in submitted code. Multithread capture always uses chronological capture
and also records `thread_start`, `thread_death`, and `exception` steps whatever
the selection; these can have no source location (line 0). See
[result contracts](output.md#result-contracts) and
[uncaught exceptions](output.md#uncaught-exceptions).

## Debug source path

The name a trace uses for a submitted file: its declared package directory plus its
file name, such as `demo/Main.java`. It keys the result's `sources` map, appears in
`entryFile` and in step and frame `file` fields, and is what file-qualified
breakpoints match. It can differ from the path written in a
[delimited source bundle](#delimited-source-bundle), such as `src/demo/Main.java`,
which `list-breakpoints` reports. See
[shared source metadata](output.md#shared-source-metadata).

## Dedicated guest

A [guest](#guest) JVM launched for one job and discarded with it. CLI `trace` and
every multithread job use one. It follows ordinary JVM lifetime, so non-daemon
threads can keep it running after `main` returns, and its launch counts against
the tracing deadline. See [guest modes](../BOUNDED_TRACING.md#guest-modes).

## Delimited source bundle

One text value that carries several Java source files, each starting after a
delimiter line such as `// --- demo/Helper.java ---`. Every entry point accepts a
bundle wherever it accepts source text, and Java callers can build one with
`SourceBundle`. Text with no delimiter line is a single file. See
[delimited source bundle](source-format.md).

## Entry file

The first submitted file, in bundle order, that contains a recognized main-method
candidate. The result's `entryFile` names it by its
[debug source path](#debug-source-path). See
[entry file and file counts](source-format.md#entry-file-and-file-counts).

## Event

A [step](#step)'s `event` label. Single-stack capture labels every step
`step_line`, including main-exit and exception snapshots. Multithread capture
also uses `main_exit`, `exception`, `thread_start`, and `thread_death`. See
[step fields](output.md#step-fields).

## Format

The JSON schema of a trace [payload](#payload): Python Tutor JSON (`pytutor`, the
default) or modern JSON (`modern`). Both are written from the same snapshots, so
the format changes how states are written, not which states are captured.
Multithread capture requires `modern`. See
[two representations of the same trace](../explanation/trace-model.md#two-representations-of-the-same-trace).

## Guest

The separate JVM that runs the submitted program while the
[calling process](#calling-process) observes it through the Java Debug Interface
(JDI). Guest input and output belong to the traced program, not to the tracer. A
guest is either [dedicated](#dedicated-guest) or [reusable](#reusable-guest). See
[guest modes](../BOUNDED_TRACING.md#guest-modes).

## Heap

In a snapshot, the objects reachable from the captured stacks and static fields.
Each has an identity that references point to. It is not a dump of the whole JVM
heap, and identities are meaningful only within one result. See
[frames and heap objects](../explanation/trace-model.md#frames-and-heap-objects-describe-different-parts-of-the-state).

## Input observation

How the tracer records how much of a job's standard input the program has read.
Each snapshot's `stdinConsumed` holds the input consumed so far, and `stdinOffset`
its length in UTF-16 code units. Reads through `Scanner`,
`BufferedReader.readLine`, and `IO.readln` are followed; other ways of reading may
not be. See
[input is a consumption cursor](../explanation/trace-model.md#input-is-a-consumption-cursor).

## Inspection policy

The rule for turning guest objects into heap entries while capturing a snapshot.
`TRUSTED`, the default, can call a small set of guest methods to show collections,
maps, and wrappers in logical form. `FIELDS` calls none and shows what each object
stores. Multithread capture always uses `FIELDS`. Neither policy isolates guest
code. See [inspection policies](../explanation/inspection-policies.md).

## Job

One requested trace, from reading its source to its single result: a CLI `trace`
invocation, a `Tracer.trace` call, or one NDJSON request processed by
`batch-trace` or `BatchTracer`. Each job has its own budgets, guest input and
output, and snapshots. See
[a job spans the calling process and a guest JVM](../explanation/trust-model.md#a-job-spans-the-calling-process-and-a-guest-jvm).

## Main-exit snapshot

The default capture: one snapshot taken when `main` returns normally. Breakpoint
`-1` selects it explicitly. When `main` ends with `System.exit` or an exception
thrown inside JDK code, no main-exit snapshot exists. See
[capture when main does not return](cli.md#capture-when-main-does-not-return).

## Multithread capture

Opt-in capture, selected with `--multithread` or a batch or Java `multithread`
setting, that records every application thread's stack in each step and adds
`threads` and `triggeringThreadId`. It requires modern JSON, implies chronological
capture and `FIELDS` inspection, runs each job in a fresh
[dedicated guest](#dedicated-guest), and is the only mode the `threads` and
`frames` budgets apply to. See [opt-in thread capture](output.md#opt-in-thread-capture).

## Ordinary output

CLI `trace` output without `--result-envelope`: the format's JSON at the root,
with no status wrapper, under finite default budgets. A controlled stop writes no
JSON and exits with status 3. The exit status reports trace generation, so a
trace of a program that threw an exception can still exit 0. See
[trace defaults at a glance](cli.md#trace-defaults-at-a-glance).

## Partial result

A stopped or failed [result envelope](#result-envelope) whose `trace` still holds
the snapshots completed before the stop. An unfinished snapshot is discarded. A
partial result shows execution up to the stop, not that the program finished.
[Ordinary output](#ordinary-output) never includes one. See
[trace payload](../BOUNDED_TRACING.md#trace-payload).

## Payload

The format-specific trace JSON itself. Ordinary output writes it at the root, a
result envelope carries it in `trace`, and Java `TraceOutcome.traceJson()`
returns it. See [trace payload](../BOUNDED_TRACING.md#trace-payload).

## Phase

The envelope's `phase` field: the last processing stage the entry point recorded,
such as `source`, `compile`, `trace`, or `serialize`. The same outcome can report
different phases from different entry points, so read it with `status` and
`stopReason` rather than alone. The *trace phase* is the period after preparation
in which the guest runs under the tracing deadline. See
[reported phases](../BOUNDED_TRACING.md#reported-phases).

## Presentation options

Settings that change how captured states are written, not which states are
captured: `--type-style`, `--remove-main-args`, `--remove-method-this`,
`--inline-strings` (Python Tutor only), primitive binary metadata, and the matching
batch and Java settings. See
[choose presentation and budgets](../guides/tracing.md#choose-presentation-and-budgets).

## Result envelope

Versioned JSON that wraps a trace [payload](#payload) with the job's outcome:
`schemaVersion` 1, `format`, `status` (`completed`, `stopped`, or `failed`),
`stopReason`, `phase`, `complete`, the effective `limits`, `counters`,
`diagnostics`, and the guest's bounded `stdout` and `stderr`. CLI
`trace --result-envelope`, Java `TraceOutcome.json()`, and each batch response's
`result` share this schema. Its `trace` is `null` when no payload is available.
See [envelope](../BOUNDED_TRACING.md#envelope).

## Reusable guest

A [guest](#guest) JVM running the tracer's harness, which loads each single-stack
Java or batch job through its own class loader. A batch worker keeps one across
jobs until its configured job count is reached or it must be replaced;
`Tracer.trace` uses one for a single call. A job ends when its entry method
returns: lingering threads are interrupted, and `System.exit`, even with status 0,
is reported as `guest_exit`. Contributor documentation calls it the persistent
guest. See [guest modes](../BOUNDED_TRACING.md#guest-modes).

## Runner

A separate hosting service, not part of this project, that isolates each untrusted
job, including its source parsing and compilation, and enforces whole-job limits.
See the [runner contract](../RUNNER_CONTRACT.md).

## Selected-breakpoint capture

Capture at the [breakpoints](#breakpoint) chosen with `--breakpoints` (`-b`) or a
`breakpoints` list, without chronological capture. Each location keeps its latest
hit unless `--accumulate-breakpoints` (batch `accumulateBreakpoints`, Java
`accumulate`) keeps every hit. The result's shape depends on the entry point; see
[result contracts](output.md#result-contracts).

## Single-stack capture

The default: each snapshot records only the stack of the thread that stopped, with
no thread identities. It works with both formats. Java and batch single-stack
jobs run in a [reusable guest](#reusable-guest). See
[trace a multithreaded program](../guides/tracing.md#trace-a-multithreaded-program).

## Snapshot

One captured execution state, recorded while the guest is paused at a stop: the
location, the captured stacks, static fields, the reachable [heap](#heap), and the
output and input observed so far. A final or refreshed snapshot can also carry
output printed after its stop; see
[input and output observations](output.md#input-and-output-observations). A stop
at a source line can come before that line runs. A snapshot is kept whole or not at all. See
[a source stop is a point in execution](../explanation/trace-model.md#a-source-stop-is-a-point-in-execution).

## Step

A [snapshot](#snapshot) as written in trace JSON: an element of a modern `steps`
array or a Python Tutor `trace` array, or a value (or an element of an array value)
in a modern `breakpoints` map. A modern step's `step` number is its one-based
position within its emitted sequence. Numbering restarts within each `breakpoints`
value, and it is not an instruction count. See [step fields](output.md#step-fields).

## Stop reason

The envelope's `stopReason`: `null` when the job completed, otherwise a stable code
such as `snapshot_limit`, `timeout`, `compile_error`, or `guest_exception`.
Budgets, the timeout, and other controlled stops give status `stopped`; codes
ending in `_error`, `guest_exception`, and `guest_exit` give status `failed`.
Ordinary CLI tracing prints the reason on stderr instead. See
[stop codes](../BOUNDED_TRACING.md#stop-codes).

## Worker

In batch processing, one of the job slots set by `--workers` or the `BatchTracer`
builder's `workers`. Each worker runs one job at a time and keeps a reusable guest
for single-stack jobs, replacing it after `--max-jobs-per-worker` jobs or when
needed. See
[a `BatchTracer` owns long-lived workers](../explanation/java-lifecycle.md#a-batchtracer-owns-long-lived-workers).
