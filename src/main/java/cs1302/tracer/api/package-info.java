/**
 * Supported Java interface for source bundles, configured binary dependencies, tracing, discovery, and NDJSON batch
 * processing.
 *
 * <p>Choose the entry point for the work being performed:</p>
 * <ul>
 * <li>{@link cs1302.tracer.api.Tracer#trace(cs1302.tracer.api.TraceRequest) Tracer.trace} executes one request,
 * owns source preparation and guest cleanup, and returns a detached {@link cs1302.tracer.api.TraceOutcome}.</li>
 * <li>{@link cs1302.tracer.api.Tracer#discoverBreakpoints(String) Tracer.discoverBreakpoints} compiles submitted
 * sources and returns executable locations or preparation diagnostics without launching a guest.</li>
 * <li>{@link cs1302.tracer.api.BatchTracer} processes NDJSON through reusable workers. Close each processor with
 * try-with-resources, call its stream processor sequentially, and retain ownership of input/output streams.</li>
 * </ul>
 *
 * <p>Outcomes contain detached metadata and serialized JSON without parser, serializer, or debugger objects.
 * Inspect reported status, completion, stop reason, diagnostics, and guest stderr; see
 * {@link cs1302.tracer.api.TraceOutcome} for semantics and guest-exception classification limitations.
 * An overload of {@code Tracer.trace} opts into primitive binary metadata in either format; descriptors describe
 * captured values rather than physical guest storage. The current development
 * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md">output reference</a> and
 * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/BOUNDED_TRACING.md#envelope">
 * envelope reference</a> define serialized consumer contracts independently of Java API compatibility.</p>
 *
 * <p>{@link cs1302.tracer.api.SourceBundle} captures ordered source text with explicit or inferred logical names
 * and emits the existing delimiter format. Its builder reads UTF-8 files when added; built values and copy builders
 * do not depend on those files remaining available. Text and fresh UTF-8 streams can be passed to existing
 * integrations. Writing leaves the caller's output stream open and unflushed. Bulk additions capture explicit
 * glob matches in lexical path order, updating the builder only after all selected files validate. Construction
 * checks naming and framing, without compiling Java or proving that all dependencies are supplied. See
 * {@link cs1302.tracer.api.SourceBundle.Builder} for glob syntax, link handling, and file validation.</p>
 *
 * <p>{@link cs1302.tracer.api.TraceClassPath} records ordered binary directory/JAR paths and explicit glob
 * selections for tracing and discovery overloads. Files are resolved when each job is prepared, independently
 * of source-discovery policy. Dependencies are borrowed by default and must remain available and unchanged;
 * opt-in copying captures the available local manifest closure before compilation. Copying preserves JAR contents
 * and signatures and does not provide filesystem isolation or an atomic snapshot. The job owns archive views
 * and copies. Glob discovery skips links by default, directory copying rejects links by default, and opt-in
 * following can capture outside targets. Source limits do not bound binary dependencies. Guest compilation
 * disables annotation processing; generated sources must be provided explicitly.</p>
 *
 * <p>Since 4.1.0, requests, limits, outcomes, and batch processors have nested builders.
 * {@code TraceRequest}, {@code TraceLimits}, and {@code TraceOutcome} also support copying through
 * {@code toBuilder()}. Builders are mutable and not thread-safe;
 * immediate collections are detached, while existing constructors retain their ownership contracts.
 * Outcome builders store supplied JSON verbatim, without synchronizing metadata edits. Batch builders require
 * explicit numeric settings and create independently owned processors only when built.</p>
 *
 * <p>Run with a full JDK 21 or later and the tracer's runtime dependencies. Guest language features depend on
 * the active JDK and parser support. These APIs use the running JDK without the CLI's environment preflight.
 * Single-stack tracing uses the reusable guest harness; a batch processor can retain its guest between jobs.
 * Application loader cleanup and selected guest-global resets do not establish a fresh JVM or complete isolation.
 * Use one job per worker lifetime when a fresh batch guest is required; see {@code BatchTracer} for its limits.</p>
 *
 * <p>Guest execution is not a sandbox, and source parsing and compilation run in the host process even for
 * breakpoint discovery. Resource budgets and field-only inspection do not provide whole-job isolation.
 * Run trusted programs directly; isolate hosted student submissions according to the
 * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/RUNNER_CONTRACT.md">runner contract</a>.</p>
 *
 * <p>Signatures use project-owned and ordinary Java types; {@link org.apiguardian.api.API} supplies stability
 * metadata. Existing types are {@code STABLE} since 4.0.0; {@code SourceBundle}, {@code TraceClassPath}, and new
 * builders are stable since 4.1.0, with compatibility throughout the 4.x series.
 * API Guardian is a transitive compile dependency. Implementation packages under
 * {@code cs1302.tracer.internal} are unsupported and may change independently.</p>
 *
 * @see cs1302.tracer.api.Tracer
 * @see cs1302.tracer.api.TraceRequest
 * @see cs1302.tracer.api.TraceOutcome
 * @see cs1302.tracer.api.BatchTracer
 * @see cs1302.tracer.api.SourceBundle
 * @see cs1302.tracer.api.TraceClassPath
 */
package cs1302.tracer.api;
