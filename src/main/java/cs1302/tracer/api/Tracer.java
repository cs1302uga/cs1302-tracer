package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.SourcePreparation;
import cs1302.tracer.internal.trace.BreakpointReader;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.batch.BatchTraceWorker;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.serialize.TraceRendering;

/** Executes self-contained trace jobs while owning compilation and guest cleanup. */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class Tracer {

    /** Prevents utility construction. */
    private Tracer() {} // Tracer

    /**
     * Traces a program and returns a detached result in the existing result-envelope format.
     * Expected compilation and execution failures are represented by the outcome.
     *
     * @param request Source, capture selection, and resource policy.
     * @return Completed, stopped, or failed job outcome.
     */
    public static TraceOutcome trace(TraceRequest request) {
        return trace(request, false);
    } // trace

    /**
     * Traces a program with optional binary metadata alongside primitive literal values.
     * Existing values and capture budgets are unchanged; enabled output is larger.
     * Binary encodings describe the values received through JDI, not physical guest storage.
     *
     * @param request Source, capture selection, and resource policy.
     * @param includePrimitiveBits Whether to include primitive binary metadata in either format.
     * @return Completed, stopped, or failed job outcome.
     * @since 4.1.0
     */
    public static TraceOutcome trace(TraceRequest request, boolean includePrimitiveBits) {
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            TraceResult result = worker.execute(request.source(), request.stdin(),
                    TraceJob.fromJava(request, includePrimitiveBits));
            return TraceRendering.freeze(result, false);
        } // try
    } // trace

    /**
     * Compiles submitted sources and discovers executable lines without running guest code.
     *
     * @param source Single-file or delimited multi-file source text.
     * @return Executable locations or preparation diagnostics.
     */
    public static BreakpointDiscovery discoverBreakpoints(String source) {
        try (SourcePreparation prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
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
