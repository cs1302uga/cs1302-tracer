# Generic inference assessment after 3.2.2

This report records the initial assessment before production integration. See
[Simple expression inference integration](INTEGRATION.md) for the subsequent
implementation and its 3.2.3 validation. The saved measurements and test counts
below belong to the stated historical revisions, not the current development
checkout. The current supported inference contract is in the
[output reference](../../docs/reference/output.md#generic-method-variable-types).

The test-only prototype recovers nine additional bindings in a 26-program corpus,
with no incorrect specializations in that corpus. All four proposed extensions
have positive examples: simple expressions, exact parameterized formals,
consistent multiple arguments, and existing reference-array arguments to varargs
methods. These results justify a narrowly scoped integration proposal, not
production adoption. The production resolver was unchanged during this initial assessment.

## Baseline and scope

The baseline is `v3.2.2`, commit
`fd2eb80af42909e9ea01a4d53f75da1924be556f`, from
[PR 11](https://github.com/cs1302uga/cs1302-tracer/pull/11).
The PR's constructor-caller, declaring-identity, concrete-type validation, and
frame-context caching review findings were repaired before merge. Existing tests
for these fixes are regression controls, not evidence of outstanding defects.

This assessment adds three files under `src/test/java/cs1302/tracer/trace/`:

- `GenericInferenceCorpus.java`: 26 complete, compilable Java programs.
- `GenericInferencePrototype.java`: an isolated structural matcher with no runtime
  values, compiler-oracle input, shared caches, or production integration.
- `GenericInferenceAssessmentTest.java`: compiler attribution, release resolver
  characterization, real traces, serializer checks, timing samples, and additional
  conservative-boundary and isolation checks.

These are experimental test sources. In the current tree they live under
`src/test/java/cs1302/tracer/internal/trace/` following the 4.0 package migration.
They are not included in the executable JAR.
The prototype deliberately handles only one unbounded method type parameter. It
is an extension experiment, not a replacement resolver; in particular, explicit
arguments remain the responsibility of the existing implementation.

## Reproduce

From the repository root, using a full JDK 21 or 25:

```sh
./mvnw -B -ntp -Dtest=GenericInferenceAssessmentTest,GenericInvocationTest,InvocationTypeResolverTest -Djacoco.skip=true test
```

The initial run contained 52 tests and wrote the saved release observations below.
The current test harness now expects the three integrated expression cases to
specialize and writes `target/generic-inference-current.json`. The original corpus
still records the release expectations separately. JaCoCo is disabled only for
this focused run because normal coverage gates require the complete suite. The full verification command is:

```sh
./mvnw -B -ntp clean package
```

## Method and acceptance criteria

Every corpus program is analyzed with the public javac APIs (`JavacTask`, `Trees`,
and `TreePathScanner`), with `--release 21` and annotation processing disabled.
Each fixture's generic helper returns `T`, allowing the attributed invocation
expression to expose the inferred binding, including assignment target typing.
Analysis emits no classes and executes no code. Invalid programs fail the test.
Capture identifiers are unstable, so their expected strings use a capture prefix;
concrete specializations require exact equality with the attributed type.

The release path is exercised through its actual caller-line and descriptor
matching, using JDI mirrors for the unit-level check. Each corpus program is also
compiled and traced in a guest JVM with instructor limits, including a ten-second
tracing deadline. Its null `T local` ensures that a runtime value cannot accidentally
supply the expected type. Modern and PyTutor output are checked in every type style.

The prototype independently matches declared argument types to formal type
structures. It specializes only when every constraint agrees and every resulting
type is concrete. No wildcard approximation, least-upper-bound computation,
boxing conversion, inheritance traversal, or target-context inference is attempted.
Unsupported expressions and solver exceptions return no binding.

For each prototype specialization, a copy of a real snapshot is relabeled in the
test and serialized in both formats and all styles. This checks representation
compatibility only. It does not test prototype integration into frame extraction.

The acceptance criterion is zero incorrect specializations in the assessment
corpus. Conservative unresolved fallback is acceptable. Any counterexample blocks
the affected rule; passing this finite corpus is not a proof of soundness.

## Results by capability

In the corpus, the release specializes four cases and conservatively falls back
in 22. The prototype specializes 12: three overlapping controls and nine new
cases. The fourth release control is an explicit `<Number>` call, which the
prototype intentionally leaves to the release path. All emitted bindings agree
with javac. There are no tracing failures in the corpus.

| Capability | Minimal call and declaration | Release | Prototype | Decision |
| --- | --- | --- | --- | --- |
| String literal | `<T> T helper(T x)`; `helper("a")` | Unresolved | `String` | Candidate for integration proposal |
| Object creation | Same helper; `helper(new Person())` | Unresolved | `Main.Person` | Candidate; excludes diamond and anonymous classes |
| Non-generic method expression | Same helper; `helper(String.valueOf(42))` | Unresolved | `String` | Candidate; nested generic calls remain deferred |
| Exact parameterized formal | `<T> T helper(List<T> x)` with `List<String>` | Unresolved | `String` | Candidate without inheritance conversion |
| Nested parameterization | `List<List<T>>` with `List<List<String>>` | Unresolved | `String` | Positive structural matching case |
| Parameterized array | `List<T[]>` with `List<String[]>` | Unresolved | `String` | Positive structural matching case |
| Consistent multiple arguments | `<T> T helper(T a, T b)` with two `String` variables | Unresolved | `String` | Candidate without common-supertype inference |
| Mixed consistent constraints | `helper(List<T>, T)` with `List<String>` and `String` | Unresolved | `String` | Positive shared-binding case |
| Fixed-arity varargs | `<T> T helper(T... x)` with `String[]` | Unresolved | `String` | Candidate only for verified array matching |
| Boxing | `helper(1)` | Unresolved | Unresolved | Defer conversion rules |
| Inherited parameterization | `helper(List<T>)` with `ArrayList<String>` | Unresolved | Unresolved | Defer generic supertype traversal |
| Conflicting arguments | `helper(T, T)` with `String` and `Object` | Unresolved | Unresolved | Defer common-supertype inference |
| Expanded varargs | `helper(T...)` with a `String` variable | Unresolved | Unresolved | Defer varargs expansion |
| Primitive-array varargs | `helper(T...)` with `int[]` | Unresolved | Unresolved | Must not assume fixed arity; javac infers `T = int[]` |
| Assignment target | `String x = helper()` | Unresolved | Unresolved | Defer target typing |
| Null plus target | `String x = helper(null)` | Unresolved | Unresolved | Defer target typing and null constraints |
| Wildcard-containing binding | `helper(T)` with `List<? extends Number>` | Unresolved | Unresolved | Preserve capture semantics |
| Captured element | `helper(List<T>)` with `List<? extends Number>` | Unresolved | Unresolved | Defer representation of capture identity |
| Intersection | `helper((Runnable & Serializable) () -> {})` | Unresolved | Unresolved | JavaParser call resolution throws; fallback is safe |
| Raw parameterized argument | `helper(List<T>)` with raw `List` | Unresolved | Unresolved | Do not silently specialize unchecked invocations |
| Nested generic invocation | `helper(Collections.emptyList())` | Unresolved | Unresolved | Defer poly-expression inference |
| Diamond creation | `helper(new ArrayList<>())` | Unresolved | Unresolved | Defer context-sensitive creation |

Controls cover inferred `String`, declared `Number` holding an `Integer`, explicit
`<Number>`, and `T = int[]` for an ordinary `helper(T)` call. The last control is
different from the primitive-array varargs case: matching a primitive array as
`T` is safe, but stripping its array dimension to infer primitive `T` is not.

The existing integration tests also confirm fallback for multiple matching calls
on one source line and missing source resolution. These are attribution gaps;
relaxing inference guards cannot resolve them. Overloads with different erased
descriptors on the same line remain a successful attribution control.

Additional prototype checks reject bounded method type parameters, multiple method
type parameters, and forwarding a class field whose type variable is shadowed by a
method variable. Twelve independent tasks across four threads perform 480 repeated
lookups using fresh source units, alternating `String` and declared `Number`, with
no binding leakage. Existing release tests exercise real recursive/forwarding
frames, concurrent guest threads, persistent batch reuse, and 31-frame context
reuse with receiver invalidation.

## Safety findings and next decisions

1. **Do not remove all inference guards.** The prototype's positive results depend
   on exact type constructors, one unbounded method type parameter, independent
   argument types, and consistent constraints. The negative cases are part of
   the adoption criteria.
2. **Propose simple-expression support first.** It has three positive cases and
   can reuse existing caller attribution. Expand adversarial tests for overloaded
   expressions, generic receivers, nested calls, anonymous types, and bounds
   before integrating it.
3. **Propose structural matching separately.** Exact parameterized matching and
   consistent arguments share useful logic, but need production declaring-identity
   bindings, forwarding substitutions, and frame-context invalidation integration.
   Do not use the prototype's standalone map as a replacement for those contexts.
4. **Treat varargs as its own rule.** Existing reference-array arguments have a
   promising subset; primitive-array and expanded calls demonstrate why arity
   alone is insufficient.
5. **Keep target typing, capture/intersection representation, and improved
   caller attribution deferred.** The compiler oracle establishes expected types
   but does not establish the feasibility or cost of integrating javac metadata
   into production. Intersection support is also limited by the current solver.

Before adopting any rule, run the same corpus through an integrated candidate,
including all negative controls and the complete release suite. Add actual new-rule
forwarding, recursion, thread and batch tests; synthetic serializer relabeling
cannot establish those properties. Define an acceptable end-to-end tracing
regression budget before making a performance adoption claim.

The release documentation's requirement for fully qualified concrete references
is narrower than its final implementation: default-package references and primitive
arrays were accepted in the merged repair. This assessment retains the release
snapshot and relies on code and regression tests for that distinction.

## Initial assessment validation and performance evidence

Validation on macOS arm64:

- JDK 25.0.4.1: full `clean package` passed, 659 tests, zero failures/errors/skips,
  zero Checkstyle violations, and all configured coverage gates met.
- JDK 25.0.4.1 and 21.0.12.1: the focused 52-test assessment and invocation controls
  passed without coverage instrumentation. Both use Java 21 source semantics.
- The fixture verifier's five unit tests passed; all 24 committed trace fixtures
  passed, including both generics formats.
- At assessment time, production sources and `pom.xml` matched the pinned release. The executable JAR
  contains none of the three new assessment classes.

Saved per-case observations are in [JDK 25 results](results-jdk25.json) and
[JDK 21 results](results-jdk21.json). Capture numbers and nanosecond timings vary
between runs; the concrete binding results agree across both JDKs.

Each timing sample uses ten warm-up iterations followed by 100 invocations on
one already-parsed source unit. The release sample includes source-call matching;
the prototype sample starts at an identified call expression. Therefore these
are cost observations for different boundaries, **not a speedup comparison**.
The real trace sample includes compilation, tracing, and both sets of serializer
checks. It is not a pure debugger latency measurement.

| JDK | Release 100 calls, median (range), ms | Prototype 100 calls, median (range), ms | Compile/trace/serialization per fixture, median (range), ms |
| --- | --- | --- | --- |
| 25.0.4.1 | 3.61 (1.06–37.20) | 1.88 (0.43–24.79) | 91.62 (73.03–249.23) |
| 21.0.12.1 | 3.86 (1.05–44.42) | 2.59 (0.42–26.98) | 91.59 (75.00–267.93) |

These single-process samples are sensitive to JIT compilation, GC, execution
order, and host activity; fixture verification overlapped part of the timing run.
No integrated prototype tracing overhead has been measured. The existing deep
frame test verifies context reuse and invalidation behavior, not a latency budget.
No production performance acceptance claim follows from these measurements.
