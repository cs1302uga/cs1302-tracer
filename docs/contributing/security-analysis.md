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

## Nesting boundaries

Representation safety checks apply even when resource budgets are unlimited:

- Inline `TraceValue` paths may contain at most 32 values, including the root and
  leaf. The validator checks heap entries, static fields, event-stack locals, and
  every captured thread's locals before snapshot byte accounting or conversion.
  It uses identity tracking, so mutable inline cycles fail without calling record
  `hashCode`, while aliases and cyclic guest heap references remain valid. Cached
  subtree heights keep shared inline graphs from expanding during validation.
- Batch JSON permits at most 64 simultaneously open arrays and objects. A streaming
  pass checks unknown properties too, before Gson constructs the request. Rejection
  produces `json_nesting_limit` in phase `parse`, with a null correlation ID because
  request decoding did not complete. Following jobs continue normally.
- Type AST paths may contain at most 384 nodes, with at most 66 non-array
  nodes on a path. The separate ceiling permits legal array dimensions while
  bounding recursive generic-type printing. Type fragments passed to substitution are checked before parsing: at most 64 open delimiters and 384 dots.
  The fragment check is conservative, including annotation text; it is not a lexer
  or a source-file nesting validator. Replacement bindings and the resulting AST
  are checked as well.

Library callers receive `NestingException` with `value_nesting_limit`,
`inline_value_cycle`, or `type_nesting_limit`. Session snapshot rejection stops the
job with that reason and retains only previously committed snapshots. These are
fixed representation ceilings, not additional configurable `TraceLimits` fields.
Callers must not mutate snapshots during validation or serialization. The shared
Gson accessor is not a general-purpose validator for arbitrary caller-built models.

Regression tests exercise boundary depths, 10,000-level invalid inputs, cycle and
alias handling, batch recovery, and a disposable JVM with a 512 KiB stack. These
checks do not guarantee operation with arbitrarily small JVM stacks or constrain
all recursion in JavaParser, its symbol solver, or javac. Hosted source parsing and
compilation still belong in the disposable whole-job process described by the
runner contract; a persistent guest JVM does not isolate those host operations.

## Reviewed alerts

These dispositions apply to the current local CLI contract, not to every possible
service wrapping the tracer. Revisit them if input ownership or execution changes.

| Alert | Disposition and evidence |
|---|---|
| User-selected file paths | `--input` and `--stdin-file` intentionally read paths chosen by the CLI caller. Adjacent source discovery is documented. Hosted access must be constrained by the runner. This is distinct from streamed source destinations, which already reject escaping or duplicate paths. |
| JDI command concatenation | JDI requires an options string. The shared launcher quotes the classpath using its configured tokenizer; it does not invoke a shell. A structural concatenation alert can remain even though the quoted-path regression passes. |
| Recursive serializers | Inline value conversion and reference-chain sampling use iterative traversal. Snapshot accounting and both serializer entry points reject inline identity cycles and paths longer than 32 values before reaching Gson. Heap references remain leaves; shared inline values on separate paths are allowed. |
| Recursive type traversal | Tracer-owned type resolution and substitution use child-before-parent work lists. AST depth and type-fragment checks precede recursive JavaParser helpers. Tests retain 255 array dimensions and 32 nested generic levels. Full source parsing, symbol resolution, and compilation still require runner resource isolation. |
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
