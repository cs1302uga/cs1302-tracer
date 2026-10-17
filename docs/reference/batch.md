# Batch protocol

`batch-trace` reads one JSON request per line (NDJSON) and writes a response per
job. Requests contain source text and may explicitly select binary dependencies
on the tracer's host filesystem. Input is UTF-8 and blank
lines are ignored. Use `id` to correlate
responses. Results default to submission order, even when workers finish out of
order. Use `--completion-order` to emit each response when it finishes.

```sh
java -jar target/code-tracer-jar-with-dependencies.jar batch-trace \
  --workers 2 --max-in-flight 16 --max-jobs-per-worker 100 < jobs.ndjson
```

Example request (one physical line):

```json
{"id":"hello","source":"public class Main { public static void main(String[] args) { System.out.println(42); } }","format":"modern","allBreakpoints":true,"limits":{"timeoutMillis":5000,"snapshots":1000,"outputBytes":65536,"heapObjects":1000,"elements":10000,"traceBytes":33554432,"sourceBytes":262144,"sourceFiles":32},"inspection":"FIELDS"}
```

The response has `id` and `result`, where `result` uses the
[version 1 envelope](../BOUNDED_TRACING.md). Inspect each result's status; successful
stream processing does not mean every job completed successfully.

| Request field | Meaning / omitted behavior |
| --- | --- |
| `id` | Optional string correlation identifier, returned unchanged; omitted or null stays null. Supply a distinct value for each job. |
| `source` | Required, nonblank Java source text, optionally a delimited multi-file bundle. |
| `format` | `pytutor` (default) or `modern`. |
| `stdin` | Guest standard input string; omitted or null means empty input. |
| `breakpoints` | Array of line or file-qualified breakpoint strings, such as `["12", "Helper.java:5"]`; `"-1"` selects main exit. |
| `allBreakpoints`, `accumulateBreakpoints` | Boolean capture options, false when omitted or null. |
| `inlineStrings`, `removeMainArgs`, `removeMethodThis` | Boolean presentation options, false when omitted or null. |
| `includePrimitiveBits` | Optional boolean, false when omitted or null; adds primitive binary metadata alongside existing values. |
| `classPath` | Optional ordered array of literal path strings, `{ "path": "..." }` objects, or `{ "root": "...", "glob": "..." }` objects; omitted or null means no binary dependencies. |
| `copyClassPath` | Optional boolean, false when omitted or null; capture dependency copies before compilation for this job. |
| `followClassPathLinks` | Optional boolean, false when omitted or null; follow links encountered during dependency glob discovery and directory copying. |
| `typeStyle` | `fqn` (default) or `simple`. |
| `limits` | Envelope limits using camelCase names; omitted limits are unlimited. |
| `inspection` | `TRUSTED` (default) or `FIELDS`. |
| `multithread` | Opt-in platform-thread capture; requires `modern`, implies chronological capture and field-only inspection. |

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

Each worker runs one job at a time in its own guest JVM. Single-stack jobs reuse
guests and recycle them after the configured number of jobs. Multithread jobs use
a fresh JVM so normal non-daemon thread lifetime applies, and the guest is cleaned
up before the worker is reused. A failed or limited job does not cancel its peers.

Try the runnable fixture in `examples/concurrent-batch/jobs.ndjson` and compare
submission order with `--completion-order`.

Class-loader separation does not provide OS isolation. Use batch tracing only for
trusted workloads or within suitable external isolation. Hosted untrusted jobs
must use one disposable tracer process per job under the
[runner contract](../RUNNER_CONTRACT.md).

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

An exception escaping the student entry point produces `status: "failed"`,
`stopReason: "guest_exception"`, and `complete: false`, with the useful partial
trace retained. The harness prints the original exception message and stack trace
into the counted guest stderr stream before the job completion barrier. Student
frames, causes, and suppressed exceptions are preserved; the internal reflective
entry-point invocation and harness frames are omitted. Earlier student stderr is
retained before the exception output. Diagnostics remain separate metadata and
must not be interpreted as a complete captured stack trace.

For legacy Python Tutor output, the terminal snapshot remains `step_line` at the
failing source location. It can immediately follow a pre-statement snapshot at
that same line. After draining output, the terminal snapshot contains the final
stdout and stderr, including the uncaught exception. Consumers must retain this
last snapshot rather than deduplicating snapshots by source location. Finite
output and trace budgets still apply; inspect the envelope for limit stops.
Caught student exceptions do not terminate the trace. A subsequent job in the
same batch daemon starts with empty output streams.
