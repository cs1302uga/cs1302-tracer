package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.SourcePreparation;
import cs1302.tracer.internal.trace.BreakpointReader;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import cs1302.tracer.internal.batch.BatchJobRequest;
import cs1302.tracer.internal.batch.BatchTraceWorker;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.serialize.TraceOutcomes;

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
        BatchJobRequest job = new BatchJobRequest(null, request.source(), request.format().toString(), request.stdin(),
                request.breakpoints(), request.chronological(), request.accumulate(), request.removeMainArgs(),
                request.inlineStrings(), request.removeMethodThis(), request.typeStyle().toString(), request.limits(),
                request.inspection(), request.multithread());
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            TraceResult result = worker.execute(job, SourcePreparation.Discovery.SUBMITTED_OR_COMPILED).result();
            return TraceOutcomes.freeze(result, false);
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
