# Batch protocol

`batch-trace` reads one JSON request per line (NDJSON) and writes a response per
job. Requests contain source text, not host file paths. Use `id` to correlate
responses; workers may complete out of order.

```sh
java -jar target/code-tracer-jar-with-dependencies.jar batch-trace \
  --workers 2 --max-jobs-per-worker 100 < jobs.ndjson
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
| `id` | Correlation identifier. Supply one for each job. |
| `source` | Java source text, optionally a delimited multi-file bundle. |
| `format` | `pytutor` (default) or `modern`. |
| `stdin` | Guest standard input string. |
| `breakpoints` | Array of breakpoint strings. |
| `allBreakpoints`, `accumulateBreakpoints` | Boolean capture options. |
| `inlineStrings`, `removeMainArgs`, `removeMethodThis` | Boolean presentation options. |
| `typeStyle` | `fqn` (default) or `simple`. |
| `limits` | Envelope limits using camelCase names; omitted limits are unlimited. |
| `inspection` | `TRUSTED` (default) or `FIELDS`. |

Workers reuse guest JVMs and recycle them after the configured number of jobs.
Class-loader separation does not provide OS isolation. Use batch tracing only for
trusted workloads or within suitable external isolation. Hosted untrusted jobs
must use one disposable tracer process per job under the
[runner contract](../RUNNER_CONTRACT.md).
