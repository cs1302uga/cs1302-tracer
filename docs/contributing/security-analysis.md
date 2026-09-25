# Security analysis

CodeQL findings are reviewed against the tracer's local CLI and library contracts.
The tracer runs submitted Java code; it is not an isolation boundary. Hosting it for
untrusted users requires the controls in the [runner contract](../RUNNER_CONTRACT.md).

## Regression coverage

The September 2026 review identified three failures and added regression coverage:

- Source delimiter matching used overlapping regex quantifiers. A malformed line
  with a long tab sequence caused quadratic work. `SourceDelimiters.scan` now scans
  lines from their two ends and supplies both parsing and source-file counting.
  Tests compare accepted syntax and offsets with the previous recognizer and count
  character reads on adversarial inputs, without depending on machine speed.
  The public `DELIMITER_PATTERN` remains as a deprecated compatibility matcher
  with possessive whitespace/marker quantifiers; the same tests cover it.
- Embedded quotes in a guest classpath broke JDI's argument tokenization.
  `GuestLauncher` selects a quote character absent from the literal paths and
  configures JDI to use it. Standalone traces and the persistent harness use the
  same launcher. An integration test relocates compiled classes into a directory
  with spaces, quotes, and a conflicting quote delimiter.
- Python Tutor element-type sampling recursively followed heap references.
  Library-supplied snapshots with long or cyclic reference chains could overflow
  the stack. The walk is now iterative and tracks visited references. Tests cover
  10,000 references and a cycle. Captured guest object links normally use references
  to concrete heap values, so this also hardens the library snapshot boundary.

The private heap traversal queue uses composition rather than inheriting
`LinkedList`'s cloning contract. Its tests cover FIFO removal and deduplication
across removed references. Legacy batch dispatch explicitly requires its prepared
persistent session. Unused private parameters and redundant boxing were removed.

## Reviewed alerts

These dispositions apply to the current local CLI contract, not to every possible
service wrapping the tracer. Revisit them if input ownership or execution changes.

| Alert | Disposition and evidence |
|---|---|
| User-selected file paths | `--input` and `--stdin-file` intentionally read paths chosen by the CLI caller. Adjacent source discovery is documented. Hosted access must be constrained by the runner. This is distinct from streamed source destinations, which already reject escaping or duplicate paths. |
| JDI command concatenation | JDI requires an options string. The shared launcher quotes the classpath using its configured tokenizer; it does not invoke a shell. A structural concatenation alert can remain even though the quoted-path regression passes. |
| Recursive serializers | Captured object graphs use heap references rather than recursively embedding guest objects. Reference-chain sampling is now iterative. Directly constructed, deeply nested inline `TraceValue` containers are outside the captured representation and can still exceed serializer or Gson stack limits. |
| Recursive type traversal | Traversal descends through finite AST children. Tests cover 255 array dimensions and 32 nested generic levels. Arbitrary-depth parser/AST safety is not claimed; compiler/parser resource isolation remains a runner responsibility. |
| Int/long byte comparison | Java widens the retained-buffer size for comparison with the expected count. A simulated `Integer.MAX_VALUE + 1` target verifies that no narrowing makes the wait end early. EOF, cancellation, and timeout bound the wait. This does not promise support for multi-gigabyte in-memory trace buffers. |
| Ignored queue offer result | The worker pool is an unbounded `LinkedBlockingQueue`, populated with non-null workers. Normal queue-capacity rejection does not apply. |
| Unused pattern bindings | Java 21 type patterns require a binding without enabling preview syntax. These bindings are retained when the branch only needs the matched type. |

## Repeating the scan

Use a fresh, traced compilation of production Java sources and an explicit suite
containing official security-and-quality and security-experimental queries, plus
available Trail of Bits and Community alert queries. Enable local input sources
alongside the default remote sources. Retain the Picocli annotation and JDI launch
models with the scan artifacts and validate that the models match actual source
and sink locations.

Keep generated databases, logs, reproducers, and SARIF under `target/`; Maven
`clean` deletes that directory. Preserve artifacts elsewhere temporarily when a
clean build is required. Verify database extraction and nonempty suite resolution
before interpreting results. Preserve raw SARIF, label Community debugging records
separately, and compare findings by rule and location rather than only total count.

Run the repository's strict coverage profile, compatibility fixtures, documentation
checks, and JDK 21 integration tests after repairs. A remaining alert needs a
reviewed explanation; reducing alert counts is not itself evidence of correctness.
