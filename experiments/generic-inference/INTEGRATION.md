# Simple expression inference integration

The production candidate adds the simple-expression subset from the assessment.
For a single unbounded method type parameter with one direct `T` formal, it derives
an exact concrete type from a supported source expression. It reuses existing
caller attribution, declaring identities, snapshot-local contexts, and formatting.

The other structural-matching experiments remain test-only. Explicit type
arguments and inference from variables, fields, casts, and array accesses keep
the existing path. The release preparation targets 3.2.3 and adds its documentation
snapshot while retaining earlier release snapshots.

## Scope and failure policy

Supported expressions are string literals, explicit object construction, and
non-generic method calls with independently concrete return types. Nested arguments
and receiver expressions must also be supported. Type and expression traversal is
bounded at 32 levels. Raw types, anonymous and local classes, generic enclosing owners,
receiver-dependent return types, array-clone expressions, diamond creation, generic methods, and unsupported
poly expressions fall back. Primitive values are not boxed by the new path;
primitive arrays remain valid reference types. Solver failures preserve unresolved
labels. Bounds and multiple method type parameters are outside the new subset.

The new path uses declared source types, never runtime argument classes. A factory
returning `Number` while producing an `Integer` therefore yields `Number` labels.
No guest methods are invoked by inference.

## Evidence and performance gate

`SimpleExpressionInferenceTest` compares javac attribution with real traced
parameter and null-local labels, checking both serializers and every type style.
It includes positive cases, overloaded calls, generic receivers/owners, anonymous
classes, bounds, nested calls, and conservative fallbacks. `GenericInvocationTest`
adds actual expression-driven recursion/forwarding, multithread tracing, and
persistent batch reuse checks while retaining the original controls.

The original 26-case assessment now checks the current resolver against an explicit
current expectation: only the three simple-expression cases change. Historical
v3.2.2 observations remain in `results-jdk21.json` and `results-jdk25.json`.
Current observations are written to `target/generic-inference-current.json`.

For a bounded performance check, run the identical `SimpleExpressionCostProbe`
against an isolated v3.2.2 checkout and the candidate, without JaCoCo. It compiles
and parses each workload once, discards two warm-up traces, and records five
traces of 100 invocations each. Each trace launches a fresh guest and uses the
instructor's ten-second deadline. The four workloads use a declared variable,
a string literal, a non-generic factory, and explicit object creation.

The gate selected before comparing measurements is: every workload must complete
with 100 snapshots and no deadline failure; its candidate median must be no more
than the baseline median plus the larger of 25% or 50 milliseconds. This is a
local regression screen, not a general performance guarantee. Full trace samples
include VM launch and extraction but exclude source compilation and serialization.

## Measured regression screen

On JDK 25.0.4.1, all four workloads passed the predefined local gate. Baseline
and candidate ran sequentially, without coverage instrumentation. Raw samples:
[baseline](cost-baseline-jdk25.json) and [candidate](cost-current-jdk25.json).

| Argument | Baseline median ms | Candidate median ms | Allowed maximum ms |
| --- | --- | --- | --- |
| `value` | 1375.15 | 1388.48 | 1718.94 |
| `"word"` | 1304.75 | 1319.19 | 1630.94 |
| `number()` | 1339.12 | 1422.25 | 1673.90 |
| `new String("word")` | 1402.57 | 1325.47 | 1753.21 |

These are local five-sample medians for 100-snapshot traces. They do not establish
performance for arbitrary programs, machines, or deep stacks. The existing
frame-context tests continue to verify reuse and receiver invalidation.

## Counterexamples found and contained

JavaParser 3.28.2 reports `String[].clone()` as `Object`, while javac attributes
`String[]` (and similarly `int[]` for primitive arrays). Accepting this method
result would violate the zero-incorrect-specialization criterion. The candidate
therefore explicitly rejects array-clone expressions, including nested uses,
and has compiler-backed real-trace regressions for reference and primitive arrays.
Supporting array clone remains deferred until its special typing rule is handled
reliably. This counterexample is also a reason to keep the broader inference
experiments isolated.

Local classes are excluded as well: the solver describes a method-local `Local`
as `Main.Local`, while javac reports `Local`; the former suggests a member type
that does not exist. The candidate requires a canonical source declaration name
when an AST declaration is available, with a real-trace fallback regression.

Calls through raw generic receivers can also report a fixed parameterized return
such as `List<String>` even though javac erases it to `List`. The candidate rejects
raw receiver ancestry and implicit instance receivers whose context is not checked.
Four compiler-backed cases cover direct and inherited raw receivers and their
parameterized counterparts.

## Run the integration checks

```sh
./mvnw -B -ntp -Dtest=GenericInferenceAssessmentTest,SimpleExpressionInferenceTest,GenericInvocationTest,InvocationTypeResolverTest -Djacoco.skip=true test
./mvnw -B -ntp -Dtest=SimpleExpressionCostProbe -Djacoco.skip=true test
```

The cost probe is opt-in by class name, so normal CI does not pay for repeated
performance sampling. It writes `target/simple-expression-cost.json`. To repeat
the baseline comparison, extract `git archive v3.2.2` into a temporary directory,
copy only `SimpleExpressionCostProbe.java` into its test package, and run the same
cost-probe command there on the same JDK. Run the two measurements sequentially.

## Current correctness results

The final candidate passes 91 focused tests on JDK 21.0.12.1 and 25.0.4.1,
plus the separately selected cost probe on JDK 25. The 26-program original corpus
now specializes seven cases (the four release controls plus the three integrated
expression cases), with the other 19 retaining fallback. All concrete answers
match javac. Per-case observations: [JDK 21](current-jdk21.json) and
[JDK 25](current-jdk25.json).

The additional 29 expression scenarios exercise ten supported cases and 19
fallback cases through actual tracing and both serializers. They include the
array-clone, local-class naming, and raw-receiver counterexamples. Actual recursion, forwarding,
thread isolation, and persistent batch tests cover the newly integrated path.
No incorrect specializations were observed in this finite corpus; the deferred
cases and conservative guards remain part of the contract.

## Final verification

- Clean JDK 25 build with an isolated temporary directory: 698 tests passed, no failures/errors/skips, Checkstyle clean,
  and both normal and strict all-production coverage gates passed.
- Focused correctness suite: 91 tests passed on both JDK 21 and 25; the opt-in
  performance probe also passed its bounded workload checks on JDK 25.
- All 24 committed trace fixtures and five fixture-verifier unit tests passed.
- Executable documentation, CLI help, quickstart, JSON and batch examples passed;
  release metadata, eight documentation unit tests, site build, and links/fragments
  across the rendered pages passed.
- Experimental classes are absent from the executable JAR. Versioned release
  documentation is retained; 3.2.3 has a new documentation snapshot.

The strict `pre-commit-coverage` profile now passes with 100% production line and
branch coverage. Boundary tests exercise nested receiver forms, unsupported
expressions, mismatched formals, lost solver metadata, anonymous declarations,
and traversal limits (including cyclic owner metadata). Two impossible empty
optional branches were removed: JavaParser 3.28.2's final `getTypeDeclaration()`
returns `Optional.of(typeDeclaration)`. No coverage exclusions or thresholds changed.
The initial commit's optional coverage failure is resolved; the follow-up commit
passes the installed hook without bypassing it.

The first final build had one failure in the existing temporary-directory cleanup
test while other tracer activity changed the shared directory. The full rerun
with `-Djava.io.tmpdir=/tmp/tracer-pr-validation-d7cb2a20` passed.
