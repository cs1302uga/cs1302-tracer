package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.SourcePreparation;
import cs1302.tracer.internal.trace.BreakpointReader;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.batch.BatchTraceWorker;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.serialize.TraceRendering;

/**
 * Executes submitted-source trace jobs with optional binary dependencies while owning preparation and guest cleanup.
 * Requires a full JDK 21 or later and the tracer's runtime dependencies. Submitted-source language features
 * depend on the active JDK and the tracer's parser support. Guest execution is not a sandbox.
 *
 * <p>Source compilation requires the JDK's {@code jdk.compiler} module. Tracing also requires {@code jdk.jdi}
 * and permission to launch a child JVM through JDI's default launching connector, using the host JVM's
 * {@code java.home}. The tracer implementation must be available as a direct filesystem JAR or class directory;
 * nested-archive and other non-filesystem code-source locations are not supported for guest launch.
 * {@link #discoverBreakpoints(String)} compiles and inspects submitted classes without launching a guest.</p>
 *
 * <p>Each trace call owns its worker and releases guest and preparation resources before returning.
 * Single-stack requests use reusable-guest execution semantics: the job ends when its entry point returns,
 * and lingering job threads are interrupted. Unexpected reusable-guest termination, including
 * {@code System.exit(0)}, is reported as {@code guest_exit} unless an earlier stop or failure takes precedence.
 * Multithread requests use a fresh dedicated guest instead.
 * Both forms return detached {@link TraceOutcome} metadata and JSON. Expected validation, preparation,
 * and execution failures are reported in that outcome rather than as checked exceptions.</p>
 *
 * <p>Completion metadata reflects failures observed by the tracer. In single-stack reusable-guest execution,
 * an uncaught exception thrown inside JDK or precompiled dependency code can be printed to captured stderr
 * while the outcome still reports {@code completed} and {@code complete=true}. An uncaught throw directly
 * from submitted code is recognized as {@code guest_exception}. Do not treat completion metadata
 * alone as proof of successful guest execution.</p>
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class Tracer {

    /** Prevents utility construction. */
    private Tracer() {} // Tracer

    /**
     * Traces a program and returns a detached result in the existing result-envelope format.
     * Uses the request's limits and inspection policy; {@link TraceRequest#TraceRequest(String)} supplies
     * finite instructor limits and trusted inspection. See the class contract for outcome limitations.
     * Primitive binary metadata is omitted; use {@link #trace(TraceRequest, boolean)} to include it.
     *
     * @param request Source, capture selection, and resource policy.
     * @return Detached completed, stopped, or failed outcome, valid after guest cleanup.
     * @throws NullPointerException If the request is null.
     */
    public static TraceOutcome trace(TraceRequest request) {
        return trace(request, false);
    } // trace

    /**
     * Traces a program with optional binary metadata alongside primitive literal values.
     * Existing values and capture budgets are unchanged; enabled output is larger.
     * Binary encodings describe the values received through JDI, not physical guest storage.
     * Expected validation, compilation, and execution failures are represented by the outcome,
     * subject to the class contract's single-stack exception-recognition limitation.
     * Source preparation and guest resources are released before return. Submitted sources may use
     * the documented multi-file delimiters; neighboring filesystem sources are not discovered.
     *
     * <p>See the <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md#encoding-definitions-and-references">
     * encoding definitions and references</a> for normative specifications, informative explanations,
     * and tracer-defined conventions. Floating-point conversion follows the contracts of
     * {@link Float#floatToRawIntBits(float)} and {@link Double#doubleToRawLongBits(double)}.</p>
     *
     * @param request Source, capture selection, and resource policy.
     * @param includePrimitiveBits Whether to include primitive binary metadata in either format.
     * @return Detached completed, stopped, or failed outcome, valid after guest cleanup.
     * @throws NullPointerException If the request is null.
     * @since 4.1.0
     */
    public static TraceOutcome trace(TraceRequest request, boolean includePrimitiveBits) {
        return trace(request, TraceClassPath.empty(), includePrimitiveBits);
    } // trace

    /**
     * Traces submitted sources with binary dependencies available to compilation and execution.
     * Dependencies do not receive source-line breakpoints. Filesystem selection and optional copying
     * occur afresh for this call; borrowed dependencies must remain available and unchanged until return.
     * Expected preparation and execution failures are represented by the outcome,
     * subject to the class contract's single-stack exception-recognition limitation.
     *
     * @param request Source, capture selection, and resource policy.
     * @param dependencies Ordered binary dependency selections and ownership policy.
     * @return Completed, stopped, or failed job outcome, without primitive binary metadata.
     * @throws NullPointerException If either argument is null.
     * @since 4.1.0
     */
    public static TraceOutcome trace(TraceRequest request, TraceClassPath dependencies) {
        return trace(request, dependencies, false);
    } // trace

    /**
     * Traces submitted sources with binary dependencies and optional primitive binary metadata.
     * Uses the dependency ownership and preparation rules of {@link #trace(TraceRequest, TraceClassPath)}
     * and the binary encoding rules of {@link #trace(TraceRequest, boolean)}. Annotation processing is
     * disabled during guest compilation. Source, dependency, and guest resources are released before return.
     * See the class contract for host-runtime requirements and outcome limitations.
     *
     * @param request Source, capture selection, and resource policy.
     * @param dependencies Ordered binary dependency selections and ownership policy.
     * @param includePrimitiveBits Whether to include primitive binary metadata in either format.
     * @return Detached completed, stopped, or failed outcome, valid after guest cleanup.
     * @throws NullPointerException If the request or dependencies are null.
     * @since 4.1.0
     */
    public static TraceOutcome trace(TraceRequest request, TraceClassPath dependencies, boolean includePrimitiveBits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(dependencies, "dependencies");
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            TraceResult result = worker.execute(request.source(), request.stdin(),
                    TraceJob.fromJava(request, dependencies, includePrimitiveBits));
            return TraceRendering.freeze(result, false);
        } // try
    } // trace

    /**
     * Compiles submitted sources and discovers executable lines without running guest code.
     * Only submitted sources participate in discovery. Locations are sorted by package-relative
     * source path and then line number. Preparation failures produce diagnostics instead of locations,
     * and temporary compilation resources are released before return. Source compilation requires a full JDK;
     * discovery does not launch a guest JVM or execute submitted code.
     * Returned paths use the compiled classes' package and debug source-file name, which can differ from
     * {@link SourceBundle} logical paths. Use these paths for the file-qualified breakpoint syntax in
     * {@link TraceRequest}.
     * Discovery uses the same entry-point preparation as tracing: the first submitted file with a
     * recognized main-method candidate must contain exactly one such candidate. Missing or ambiguous
     * entry points produce preparation diagnostics, even when the source is otherwise compilable.
     *
     * @param source Single-file or delimited multi-file source text.
     * @return Executable locations or preparation diagnostics.
     */
    public static BreakpointDiscovery discoverBreakpoints(String source) {
        return discoverBreakpoints(source, TraceClassPath.empty());
    } // discoverBreakpoints

    /**
     * Compiles submitted sources with binary dependencies and discovers their executable lines.
     * Dependency classes are not scanned for breakpoints, and no guest is launched. Dependency
     * selection and optional copying occur afresh for this call; all owned resources are released
     * before return. Preparation failures produce diagnostics instead of locations.
     * Returned locations follow {@link #discoverBreakpoints(String)} path and line conventions.
     *
     * @param source Single-file or delimited multi-file source text.
     * @param dependencies Ordered binary dependency selections and ownership policy.
     * @return Executable submitted-source locations or preparation diagnostics.
     * @throws NullPointerException If dependencies are null.
     * @since 4.1.0
     */
    public static BreakpointDiscovery discoverBreakpoints(String source, TraceClassPath dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        try (SourcePreparation prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, dependencies)) {
            List<BreakpointLocation> locations = BreakpointReader.read(prepared.compilation()).entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .map(line -> new BreakpointLocation(entry.getKey(), line)))
                    .toList();
            return new BreakpointDiscovery(locations, List.of());
        } catch (IOException | RuntimeException failure) {
            return new BreakpointDiscovery(List.of(), List.of(failure.toString()));
        } // try
    } // discoverBreakpoints

} // Tracer
