# Run batch jobs

Batch mode accepts newline-delimited JSON (NDJSON): one request per physical line,
with one response per job. Start with [installation](../guides/installation.md)
and work in the directory containing `code-tracer.jar`.

## Create two requests

Save this as UTF-8 `jobs.ndjson`. Keep each object on one physical line. The first
job prints `42`; the second intentionally has a Java compilation error.

<!-- batch-jobs:start -->
```json
{"id":"hello","source":"public class Main { public static void main(String[] args) { System.out.println(42); } }","format":"modern","allBreakpoints":true,"limits":{"timeoutMillis":5000,"snapshots":1000,"outputBytes":65536,"heapObjects":1000,"elements":10000,"traceBytes":33554432,"sourceBytes":262144,"sourceFiles":32,"threads":64,"frames":4096,"snapshotBytes":8388608},"inspection":"FIELDS"}
{"id":"broken","source":"public class Main { invalid Java }","format":"modern","limits":{"timeoutMillis":5000,"snapshots":1000,"outputBytes":65536,"heapObjects":1000,"elements":10000,"traceBytes":33554432,"sourceBytes":262144,"sourceFiles":32,"threads":64,"frames":4096,"snapshotBytes":8388608},"inspection":"FIELDS"}
```
<!-- batch-jobs:end -->

These are illustrative budgets, not universal production settings. Omitted batch
limits are unlimited; tune each limit for your workload and bound the whole job
externally when required.

## Process the stream

In **Bash** or **Command Prompt**:

<!-- batch-command:start -->
```sh
java -jar code-tracer.jar batch-trace --workers 1 --max-in-flight 2 --max-jobs-per-worker 100 < jobs.ndjson > results.ndjson
```
<!-- batch-command:end -->

**PowerShell** does not support shell input redirection with `<`. For this UTF-8 text
example, use PowerShell 7.4+:

```powershell
Get-Content -Encoding utf8 jobs.ndjson | java -jar code-tracer.jar batch-trace --workers 1 --max-in-flight 2 --max-jobs-per-worker 100 > results.ndjson
```

## Inspect each result

Open `results.ndjson`. It contains two records, in submission order by default:

| Response ID | Expected result |
| --- | --- |
| `hello` | `status` is `completed`, `complete` is true, and captured stdout is `42\n`. |
| `broken` | `status` is `failed`, `complete` is false, and diagnostics describe the compilation failure. |

The process can exit successfully even though `broken` failed. A client must parse
every response and inspect `result.status` and `result.complete`; process exit
status alone describes stream processing. Use IDs to correlate jobs, especially
when adding `--completion-order` and more workers. A transport error may abort
processing before all requests receive responses; the [protocol reference](../reference/batch.md)
defines those cases.

Workers can reuse guest JVMs for single-stack jobs. Each job's source, output,
observations, and retained snapshots belong to that job; reused workers do not
provide OS isolation. Multithread jobs use dedicated guests. For hosted untrusted
submissions, follow the [runner contract](../RUNNER_CONTRACT.md), including its
one-disposable-tracer-process-per-job requirement.
