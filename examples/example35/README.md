# Example 35: Synchronized shared counter

The final count is `6`. Both workers reference the same heap object. A worker may appear BLOCKED while its peer owns the monitor; observing that state is schedule dependent.

Run from the repository root after building the JAR (JDK 21 or later):

```sh
java -jar target/code-tracer-jar-with-dependencies.jar trace \
  -i examples/example35/Driver.java --multithread --format modern \
  --result-envelope --timeout-ms 15000 --max-snapshots 500 \
  --max-threads 16 --max-frames 512 --max-snapshot-bytes 8388608 \
  --max-trace-bytes 67108864 --max-heap-objects 10000 --max-elements 100000 --pretty
```

Inspect `trace.steps[*].threads`, `triggeringThreadId`, and the shared `heap`.
Only submitted application frames are shown; a waiting thread can have an empty
application stack after its task returns. Output belongs to the entire process.
Tracing affects scheduling, so compare relationships and outcomes rather than
expecting identical snapshots between runs.
