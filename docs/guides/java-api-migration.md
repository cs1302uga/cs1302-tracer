# Migrate Java integrations from 3.x


This is a breaking Java interface change for 4.0.0. CLI syntax and serialized
Python Tutor, modern, envelope, and NDJSON defaults remain unchanged; primitive
binary metadata is an opt-in additive extension.

| Previous integration | Replacement |
| --- | --- |
| `CompilationHelper`, `SourcePreparation`, and `App.parseSource` | Submit source text to `Tracer`; compilation and AST ownership are internal. |
| `DebugTraceHelper` and JDI-based extraction helpers | `Tracer.trace(TraceRequest)` for self-contained jobs. |
| `getValidBreakpointLines` and compiled-program inspection | `Tracer.discoverBreakpoints(source)`. |
| `PyTutorSerializer.getGson()` or `ModernTraceSerializer.getGson()` | `TraceOutcome.json()`, `traceJson()`, or `writeJson(Writer)`. |
| `BatchTraceService` | `BatchTracer` with the same stream protocol. |
| `execution.TraceLimits` / `execution.InspectionPolicy` | Corresponding types in `cs1302.tracer.api`. |
| `model.TraceFormat` / `model.TypeStyle` | Corresponding types in `cs1302.tracer.api`. |
| Internal snapshot or wire records | Consume the documented JSON contract; internal records are not supported DTOs. |

Implementation classes now live under `cs1302.tracer.internal`. Some must remain
public for communication across implementation packages and guest JVM launching.
Their visibility does not make them supported library interfaces. There are no
compatibility aliases for the old parser/debugger/serializer helpers.

`Automatic-Module-Name` supplies stable module identity, not access control:
automatic modules export all packages, and classpath execution likewise does not
hide public internal classes. Explicit JPMS exports are deferred; doing that
requires validating dependency module names, Gson reflective access, and guest
launching independently for the regular JAR and executable assembly. The build
instead checks the supported declarations for dependency leakage and compiles an
external consumer with only tracer classes and API Guardian on its compile classpath.

Generated public Javadoc omits implementation packages. The repository's internal
package documentation and dependency links remain useful for contributor builds.
The tracer executes guest code and is not a security sandbox; use the separate
[runner contract](../RUNNER_CONTRACT.md) for isolation requirements.
