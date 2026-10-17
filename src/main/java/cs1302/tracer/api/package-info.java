/**
 * Supported Java interface for source bundles, configured binary dependencies, tracing, discovery, and NDJSON batch
 * processing.
 *
 * <p>{@link cs1302.tracer.api.Tracer} owns preparation and guest cleanup for one-shot jobs.
 * {@link cs1302.tracer.api.TraceOutcome} exposes detached metadata and JSON without parser, serializer, or debugger
 * objects. An overload of {@code Tracer.trace} opts into primitive binary metadata in either output format.
 * Its <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md#encoding-definitions-and-references">
 * encoding reference</a> separates normative specifications, informative explanations, and tracer conventions.
 * {@link cs1302.tracer.api.BatchTracer} owns reusable workers and must be closed by its caller.</p>
 *
 * <p>{@link cs1302.tracer.api.SourceBundle} captures ordered source text with explicit or inferred logical names
 * and emits the existing delimiter format. Its builder reads UTF-8 files when added; built values and copy builders
 * do not depend on those files remaining available. Text and fresh UTF-8 streams can be passed to existing
 * integrations. Writing leaves the caller's output stream open and unflushed. Bundle construction validates
 * framing and paths, without compiling Java or proving that all dependencies are supplied.</p>
 *
 * <p>Bulk file additions select explicit root-relative glob matches, append them in lexical path order, and
 * capture all files before atomically updating the builder. Logical names may be explicit or inferred from Java
 * declarations. Discovered symbolic links are skipped by default; the opt-in following overloads can reach
 * targets outside the selected root and reject encountered broken links or cycles. The selected root itself may
 * be a symbolic link. Glob collection does not change tracing's source-discovery policy.</p>
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
 * the active JDK and parser support. Resource budgets bound tracing work, not whole-process memory or isolation.</p>
 *
 * <p>Signatures use project-owned and ordinary Java types; {@link org.apiguardian.api.API} supplies stability
 * metadata. Existing types are {@code STABLE} since 4.0.0; {@code SourceBundle}, {@code TraceClassPath}, and new
 * builders are stable
 * since 4.1.0,
 * with compatibility throughout the 4.x series.
 * API Guardian is a transitive compile dependency. Implementation packages under
 * {@code cs1302.tracer.internal} are unsupported and may change independently. Guest execution is not a sandbox.</p>
 */
package cs1302.tracer.api;
