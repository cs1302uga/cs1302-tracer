# Batch protocol

For a two-job example with success and failure handling, see the
[batch tutorial](../tutorials/batch-jobs.md).

`batch-trace` reads one JSON request per line (NDJSON) and writes a response per
job. Requests contain source text and may explicitly select binary dependencies
on the tracer's host filesystem. Input is UTF-8 and blank
lines are ignored. Use `id` to correlate
responses. Results default to submission order, even when workers finish out of
order. Use `--completion-order` to emit each response when it finishes.

```sh
java -jar target/code-tracer.jar batch-trace \
  --workers 2 --max-in-flight 16 --max-jobs-per-worker 100 < jobs.ndjson
```

Example request (one physical line):

```json
{"id":"hello","source":"public class Main { public static void main(String[] args) { System.out.println(42); } }","format":"modern","allBreakpoints":true,"limits":{"timeoutMillis":5000,"snapshots":1000,"outputBytes":65536,"heapObjects":1000,"elements":10000,"traceBytes":33554432,"sourceBytes":262144,"sourceFiles":32,"threads":64,"frames":4096,"snapshotBytes":8388608},"inspection":"FIELDS"}
```

The response has `id` and `result`, where `result` uses the
[version 1 envelope](../BOUNDED_TRACING.md). Inspect each result's status; successful
stream processing does not mean every job completed successfully.

| Request field | Meaning / omitted behavior |
| --- | --- |
| `id` | Optional string correlation identifier, returned unchanged; omitted or null stays null. Supply a distinct value for each job. |
| `source` | Required, nonblank Java source text, optionally a [delimited source bundle](source-format.md). |
| `format` | Case-insensitive `pytutor` (default) or `modern`; omitted, null, or blank selects the default. |
| `stdin` | Guest standard input string; omitted or null means empty input. |
| `breakpoints` | Array of line or file-qualified breakpoint strings, such as `["12", "Helper.java:5"]`; `"-1"` selects main exit. |
| `allBreakpoints`, `accumulateBreakpoints` | Boolean capture options, false when omitted or null. |
| `inlineStrings`, `removeMainArgs`, `removeMethodThis` | Boolean presentation options, false when omitted or null. `inlineStrings` affects Python Tutor output only; modern output ignores it. |
| `includePrimitiveBits` | Optional boolean, false when omitted or null; adds primitive binary metadata alongside existing values. |
| `classPath` | Optional ordered array of literal path strings, `{ "path": "..." }` objects, or `{ "root": "...", "glob": "..." }` objects; omitted or null means no binary dependencies. |
| `copyClassPath` | Optional boolean, false when omitted or null; capture dependency copies before compilation for this job. |
| `followClassPathLinks` | Optional boolean, false when omitted or null; follow links encountered during dependency glob discovery and directory copying. |
| `typeStyle` | Case-insensitive `fqn` (default) or `simple`; omitted, null, or blank selects the default. Applies to type labels and [lambda signature types](output.md#lambda-implementation-types), preserving lambda body text. |
| `limits` | Object using the exact keys in [request limits](#request-limits); omitted or null means all budgets are unlimited, and omitted individual keys are unlimited. |
| `inspection` | Case-sensitive `TRUSTED` (default) or `FIELDS`. Unrecognized string tokens, including lowercase `"fields"`, are treated as omitted and silently select `TRUSTED`, unless multithread capture forces `FIELDS`. |
| `multithread` | Opt-in platform-thread capture; requires `modern`, implies chronological capture and field-only inspection. |

Unknown or misspelled request properties and limit keys are ignored without a
diagnostic. Validate requests against the exact field names before submission;
for example, `timeoutMs` does not set `timeoutMillis`, and `maxSnapshots` does not
set `snapshots`. Likewise, validate the exact inspection token: unlike `format`
and `typeStyle`, an unknown `inspection` is not a `validation_error`. `TRUSTED`
inspection can invoke guest methods. A started `FIELDS` session includes an
explanatory diagnostic, but diagnostic wording is not a stable machine-readable
policy identifier.

With no breakpoints and no chronological capture, the result contains the main-exit
snapshot. `allBreakpoints: true` selects all executable lines when `breakpoints`
is omitted or empty; a nonempty selection restricts source-line stops and retains
them chronologically. Otherwise, selected locations retain only their latest hit
unless `accumulateBreakpoints` is true. Chronological capture already retains
repeated hits. Include source dependencies in the request for reproducible jobs.
Under `TRUSTED` inspection, packaged batch sources can also discover matching
source roots in the process working directory, `src/main/java`, or `src`.
`FIELDS` and multithread capture use only submitted sources.

`includePrimitiveBits` applies independently to each job, including consecutive
jobs in a reused guest. It works with both formats and all capture selections;
multithread jobs still require modern output. See
[primitive metadata](output.md#optional-primitive-binary-metadata) for placement
and serialization cost, and
[encoding definitions and references](output.md#encoding-definitions-and-references)
for normative specifications and informative explanations. Existing values and envelope version remain
unchanged; clients requesting this extension must accept its additional fields.

`--max-in-flight` bounds admitted jobs, including running jobs and completed results
waiting for earlier responses. Its default is 16; all worker and queue settings
must be positive. Backpressure pauses input admission when that window fills.
A slow first job can delay ordered output. Memory within each job remains governed
by that job's limits; choose finite limits to bound buffered result sizes.

Each physical record is limited to 16,777,216 decoded UTF-16 code units, excluding
the line ending. This transport ceiling is separate from `limits.sourceBytes`,
which counts the source string's UTF-8 bytes after JSON decoding. An oversized
record aborts stream processing with a CLI diagnostic and exit code 1; it does
not produce a result envelope for that record. Do not assume later records ran.

Requests may contain at most 64 simultaneously open JSON objects and arrays,
including unknown properties. Deeper input returns a stopped result with
`stopReason: "json_nesting_limit"`, `phase: "parse"`, and a null `id`. Later jobs
continue. This representation ceiling also applies when job budgets are unlimited.
Malformed JSON or a literal `null` request instead returns a failed result with
`stopReason: "parse_error"`, `phase: "parse"`, and a null `id`; subsequent records
continue. These parse failures use `format: "unknown"`. A structurally parsed
request with invalid format or type-style settings uses `validation_error` and
retains its `id`.

Each worker runs one job at a time. Single-stack jobs reuse guests and recycle
them after the configured number of completed guest jobs; a terminated guest is
replaced before another job runs. Multithread jobs use a fresh JVM so normal
non-daemon thread lifetime applies, and the guest is cleaned up before the worker
is reused. A failed or limited job does not cancel its peers. See
[guest reuse between jobs](#guest-reuse-between-jobs) and the shared
[guest-mode definitions](../BOUNDED_TRACING.md#guest-modes).

Try the runnable fixture in `examples/concurrent-batch/jobs.ndjson` and compare
submission order with `--completion-order`.

Neither a fresh guest JVM nor class-loader separation provides whole-job
isolation: source parsing and compilation occur in the tracer process. Use batch
tracing directly only for trusted workloads. Hosted untrusted jobs must use one
disposable tracer process per job under the
[runner contract](../RUNNER_CONTRACT.md).

## Process exit statuses

The `batch-trace` process reports whether it processed the stream successfully.
Each response's `result.status` reports that individual job's outcome. Inspect
both levels when automating a batch:

| Process status | Meaning | Output and next action |
| --- | --- | --- |
| `0` | Input reached end-of-stream and admitted responses were written, regardless of individual job statuses. Empty input also succeeds. | Inspect every response, including `failed` and `stopped` results. Malformed requests that produce per-record parse responses do not by themselves fail the process. |
| `1` | JDK environment preflight failed or was interrupted before batch processing. | Stderr reports `code-tracer: JDK environment check failed: ...` or the interrupted-check diagnostic. No batch responses are produced. Follow [JDK environment preflight](cli.md#jdk-environment-preflight). |
| `1` | Batch setup or stream processing failed: nonpositive `--workers`, `--max-in-flight`, or `--max-jobs-per-worker`; an unreadable `--input` file; an oversized physical record; or input/output I/O failure. | The command handler writes `Batch trace failed: ...` to stderr. Preserve any responses already written; a response is not guaranteed for the offending record or later input. |
| `2` | Command-line argument parsing failed, such as an unknown option, a missing option value, or nonnumeric worker settings. | Read the usage diagnostic on stderr and compare with `batch-trace --help`. No batch work starts. |

`batch-trace --help` and `batch-trace --version` can also exit `0` without
processing input; the environment preflight still runs before those options.

Already-admitted jobs can keep executing and writing responses while the command
closes its workers after a stream-processing failure. An exit-`1` run can therefore
contain valid responses followed by incomplete or missing output. Validate each
NDJSON response independently, preserve process stderr and status, and account for
submitted records that have no response. An external kill or host failure can
also truncate a response without reaching the command's error handler.

These process statuses differ from the single-command
[`trace` exit statuses](cli.md#exit-statuses). Java callers of `BatchTracer` receive
stream-processing exceptions instead of a CLI process status; see
[Java batch integration](java-api.md#process-a-batch-stream).

## Request limits

Every budget below defaults to zero (unlimited), including fields omitted from a
partially supplied `limits` object. These are the JSON keys, not the CLI option
names or the defaults of `TraceLimits.builder()`:

| JSON key | Unit / scope | Corresponding CLI option |
| --- | --- | --- |
| `timeoutMillis` | Trace-phase milliseconds; excludes preparation and reusable-guest startup. | `--timeout-ms` |
| `snapshots` | Completed captures, including states later replaced. | `--max-snapshots` |
| `outputBytes` | Retained bytes per guest output stream. | `--max-output-bytes` |
| `heapObjects` | Distinct reachable objects per snapshot. | `--max-heap-objects` |
| `elements` | Inspected fields, array slots, frames/locals, and string storage units per snapshot. | `--max-elements` |
| `traceBytes` | Accounted snapshot storage, including retained history and the state being built. | `--max-trace-bytes` |
| `sourceBytes` | Submitted source UTF-8 bytes. | `--max-source-bytes` |
| `sourceFiles` | Delimiter-defined source files; undelimited input counts as one. | `--max-source-files` |
| `threads` | Live application threads, only in multithread capture. | `--max-threads` |
| `frames` | Total frames before application filtering, only in multithread capture. | `--max-frames` |
| `snapshotBytes` | Accounted storage for one snapshot in either capture mode. | `--max-snapshot-bytes` |

Supply every intended budget explicitly and compare an admitted job's echoed
`result.limits` with the intended configuration. Parse-error responses use fallback
limits, so check the result status and reason too. Negative budgets or a timeout
greater than `Long.MAX_VALUE / 1_000_000` milliseconds fail during request parsing,
producing `parse_error` with a null response ID. See
[bounded tracing](../BOUNDED_TRACING.md#limits) for exact accounting, cap boundaries,
fixed representation checks, and the limits of these budgets.

## Response IDs and request failures

An ID is returned unchanged only after the whole request has been deserialized.
A supplied `id` is therefore not guaranteed to survive a malformed record:

| Failure | Result | Response ID |
| --- | --- | --- |
| Invalid JSON, literal `null`, incompatible JSON structure, or invalid numeric limits during construction | `failed`, `parse_error`, phase `parse` | `null`, even when the text contains an `id`. |
| More than 64 open objects/arrays | `stopped`, `json_nesting_limit`, phase `parse` | `null`. |
| Parsed request with invalid format, type style, dependency-selection shape, or unsupported multithread format | `failed`, `validation_error`, phase `validation` | Parsed request ID, which can itself be omitted or null. |
| Source preparation, compilation, or execution failure after admission | Per-job envelope described by the [stop-code reference](../BOUNDED_TRACING.md#stop-codes) | Parsed request ID. |
| Physical record exceeds the transport ceiling, or stream I/O fails | Stream aborts; no response is guaranteed for that record or later records. | No replacement ID is invented. |

Malformed nonblank breakpoint specifications are parsed after compilation and
currently produce `failed` / `tracer_error` in phase `trace`, retaining the parsed
ID; compilation failures take precedence. Unknown property names and inspection
tokens instead follow the silent fallback rules above.

With default submission ordering, clients can maintain their own ordinal for
each nonblank input record as well as its ID. Under `--completion-order`, a
null-ID parse response cannot in general be uniquely correlated with its input.
Validate records before sending, or keep submission ordering when that correlation
is required. Do not recover an ID by interpreting diagnostic text.

## Guest reuse between jobs

Single-stack batch jobs and one-shot `Tracer.trace` calls use the reusable guest
harness. Each job loads submitted classes through a new class loader. Between
jobs the harness restores system properties, default locales, the default time
zone, standard streams, and the default uncaught-exception handler. Other
JDK-global state, such as a default `CookieHandler` or logger configuration, can
remain visible to later jobs on that guest. Filesystem changes also persist
unless the caller removes them.

Use `--max-jobs-per-worker 1`, or Java's
`BatchTracer.builder().maxJobsPerWorker(1)`, when each executed batch job needs a
fresh guest JVM. This does not reset filesystem effects or isolate the tracer
process. A one-shot `Tracer.trace` owns and closes its worker for that call.

When the entry method returns, the reusable harness interrupts lingering job
threads and joins each for up to 250 milliseconds. If any remains alive, the guest
is terminated and the result reports `guest_exit` unless an earlier stop or
failure takes precedence. Threads that end during interruption/join do not by
themselves fail the job, but their output can differ from ordinary JVM lifetime.
An explicit `System.exit`, including status zero, also terminates the reusable
guest and produces `guest_exit` unless an earlier reason wins. Dedicated guests
used by CLI `trace` and multithread jobs have different lifetime rules; see
[guest modes](../BOUNDED_TRACING.md#guest-modes).

## Binary dependencies

Since 4.1.0, each job can supply its own `classPath` selection. For example, when
`build/classes` and matching JARs exist on the tracer host, this is one physical
NDJSON request line:

```json
{"id":"with-library","source":"import teaching.Helper; public class Main { public static void main(String[] args) { System.out.println(Helper.answer()); } }","format":"modern","classPath":["build/classes",{"root":"lib","glob":"**/*.jar"}],"copyClassPath":true,"followClassPathLinks":false}
```

Strings are literal individual paths, even when they contain `*` or a native
path-list separator. They are never split or implicitly interpreted as globs.
`{ "path": "build/classes" }` is the equivalent object form. A glob entry must
contain both string fields `root` and `glob`, and no non-null `path`; a literal
entry must not contain non-null `root` or `glob`. Null entries, incomplete shapes,
and malformed glob syntax fail job validation and retain the request's `id`.
Wrong JSON types, such as a numeric entry or boolean `root`, are parse failures
with a null `id`, consistent with other malformed requests.

Selections are resolved at each job's preparation against the tracer process
working directory, not the NDJSON file's directory or a client machine. Directory
entries provide package-relative compiled classes and resources; they do not
request source compilation or include JARs within them. Globs use `/` separators
and the portable grammar described in the
[Java interface](java-api.md#binary-dependencies). Each matching group is sorted
deterministically at its supplied position, and the first normalized absolute
path wins when selections overlap. Compilation, source type analysis, and execution
share the resulting dependency order after the job's compiled output.

`copyClassPath: true` creates fresh job-owned copies before compilation, including
the available transitive local manifest dependency closure. Original JAR bytes,
manifests, and signatures remain intact. The default borrows dependencies, which
callers must keep available and unchanged for that job. Copies are removed when
the job finishes; caller-owned originals are never removed. Reusing a configuration
or processing another record performs preparation again rather than reusing a
previous job's copies or loaded classes.

By default discovered symbolic links are skipped during glob selection, while
links encountered inside a directory being copied are rejected.
`followClassPathLinks: true` permits following and capturing outside targets, and
fails on broken links or directory cycles. Explicit paths and roots retain normal
filesystem resolution. Missing explicit paths, invalid/unreadable archives, and
patterns with no matches fail during `phase: "compile"`; a later job can still run.
Missing manifest targets follow Java's ignore behavior, and manifest references
use URL syntax rather than glob patterns. Manifest directory URLs require a
trailing `/`; directories named without it and archives named with it are ignored.
Literal directory entries do not require that separator. Source-line tracing
remains limited to classes compiled for the job; dependency objects and frames
retain existing capture behavior.

This is an additive batch-input extension; response formats and envelope versions
are unchanged. Omitted settings preserve the empty dependency list. Dependency
choices apply equally to single-stack and multithread jobs. Guest compilation
disables annotation processors with `-proc:none`; supply required generated
declarations as sources. Source byte/file budgets do not bound binary dependencies
or copied resources, and trace deadlines exclude preparation. Hosted callers must
allowlist dependency paths and enforce the [runner contract](../RUNNER_CONTRACT.md).

## Uncaught student exceptions

For single-stack reusable guests, an uncaught exception thrown directly from a
compiled submitted class is recognized as `status: "failed"`,
`stopReason: "guest_exception"`, and `complete: false` unless an earlier stop or
failure takes precedence. Completed partial snapshots are retained when available. The harness prints the original exception message and stack trace
into the counted guest stderr stream before the job completion barrier. Student
frames, causes, and suppressed exceptions are preserved; the internal reflective
entry-point invocation and harness frames are omitted. Earlier student stderr is
retained before the exception output. Diagnostics remain separate metadata and
must not be interpreted as a complete captured stack trace.

There is a current recognition limitation: an uncaught exception thrown inside
JDK or precompiled dependency code, such as `Integer.parseInt("bad")`, can print
its stack trace to guest stderr while the job still reports `completed`,
`complete: true`, and a null `stopReason`. Completion metadata alone
does not establish successful guest execution. Guest stderr can also contain
ordinary program messages, so it is not a machine-readable success classifier.

Single-stack terminal exception snapshots use `event: "step_line"` in both
formats; they can follow a pre-statement snapshot at the same line. Do not
deduplicate by source location. See [uncaught exceptions](output.md#uncaught-exceptions)
for event representation, output, and mode-specific limitations. Finite budgets
still apply; inspect the envelope for limit stops. Caught exceptions do not end
the trace. A later job starts with empty job output streams, subject to the
[guest-global state caveat](#guest-reuse-between-jobs).
