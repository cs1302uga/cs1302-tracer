# Concurrent batch jobs

Run three independent jobs with two workers and at most three admitted jobs:

```sh
java -jar target/code-tracer-jar-with-dependencies.jar batch-trace \
  --workers 2 --max-in-flight 3 -i examples/concurrent-batch/jobs.ndjson
```

Results default to submission order: `workers`, `counter`, `quick`. Add
`--completion-order` to emit each result when ready, using its `id` to correlate
it with its request. Actual completion order varies. Each job has independent
limits and guest state; multithread jobs use fresh guest JVMs.

The first two sources mirror examples 34 and 35. The third is a single-threaded
job, demonstrating that the same batch can mix capture modes.
