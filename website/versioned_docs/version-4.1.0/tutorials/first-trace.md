# Your first trace

After [building the project](../guides/installation.md), run this trusted example
from the repository root:

<!-- quickstart:start -->
```sh
java -jar target/code-tracer-jar-with-dependencies.jar trace \
  -i examples/example0/Driver.java --all-breakpoints --format modern --pretty
```
<!-- quickstart:end -->

The program creates a `Person` record. The result contains its original source,
`entryFile`, and a chronological `steps` array. Each step describes its source line,
active call stack, and heap. An object reference connects a local variable to an
entry in the heap; object IDs can differ between runs.

Try `--format pytutor` to obtain Python Tutor tuples instead. See the
[output reference](../reference/output.md) for both formats.

To discover selectable breakpoints:

```sh
java -jar target/code-tracer-jar-with-dependencies.jar list-breakpoints \
  -i examples/example0/Driver.java --json --pretty
```

Replace `--all-breakpoints` with `--breakpoints LINE`, using a line from that output.
By default, selected breakpoints retain their latest hit. Add
`--accumulate-breakpoints` to retain repeated hits.

The tracer executes the supplied program. Only use trusted programs directly on
your workstation; integrations accepting untrusted submissions must implement the
[runner contract](../RUNNER_CONTRACT.md).
