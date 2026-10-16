package cs1302.tracer.internal.serialize;

import org.apiguardian.api.API;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.internal.batch.BatchJobResponse;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.model.SourceMetadata;
import cs1302.tracer.internal.model.pytutor.PyTutorTrace;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Owns trace root selection, presentation, and final JSON encoding for every caller. */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class TraceRendering {
    /** Prevents utility construction. */
    private TraceRendering() {} // TraceRendering

    /**
     * Renders ordinary output while preserving each format's historical root and whitespace policy.
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Resolved presentation choices.
     * @param captured Completed capture.
     * @param pretty Whether to indent Python Tutor output; modern ordinary output is always indented.
     * @return Complete JSON text.
     */
    public static String ordinary(String source, String stdin, TraceJob job, TraceDispatch.Capture captured,
            boolean pretty) {
        return job.format() == TraceFormat.MODERN ? modern(source, captured, stdin, job)
                : pytutor(source, captured, stdin, job, pretty);
    } // ordinary

    /**
     * Converts completed snapshots for embedding in a result; callers pass the opaque value back to rendering.
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Resolved presentation choices.
     * @param snapshots Completed states in capture order.
     * @return Opaque trace payload with a sequence root.
     */
    public static Object sequence(String source, String stdin, TraceJob job, List<ExecutionSnapshot> snapshots) {
        if (job.format() == TraceFormat.MODERN) {
            return new ModernTraceSerializer(job.removeMainArgs(), job.inlineStrings(), job.removeMethodThis(),
                    job.typeStyle(), job.includePrimitiveBits()).createTrace(source, stdin, snapshots);
        } // if
        return new PyTutorSerializer(job.removeMainArgs(), job.inlineStrings(), job.removeMethodThis(),
                job.typeStyle(), job.includePrimitiveBits()).createTrace(source, stdin, snapshots);
    } // sequence

    /**
     * Freezes a completed result before its producing job is released.
     * @param result Execution-owned outcome and opaque trace payload.
     * @param pretty Whether to indent JSON.
     * @return Detached outcome.
     */
    public static TraceOutcome freeze(TraceResult result, boolean pretty) {
        return new TraceOutcome(result.format(), result.status(), result.stopReason(), result.phase(),
                result.complete(), result.counters(), result.diagnostics(), result.stdout(), result.stderr(),
                PyTutorSerializer.getGson(pretty).toJson(result.trace()),
                PyTutorSerializer.getGson(pretty).toJson(result));
    } // freeze

    /**
     * Encodes a correlated batch response, without ordering or writing it.
     * @param response Batch-owned response and correlation.
     * @return One JSON record without a line terminator.
     */
    public static String batch(BatchJobResponse response) {
        return PyTutorSerializer.getGson(false).toJson(response);
    } // batch

    /**
     * Renders ordinary modern JSON with its historical indentation.
     *
     * @param source Source string.
     * @param captured Captured execution states.
     * @param guestStdin Standard input string for guest process.
     * @param job Resolved presentation choices.
     * @return Ordinary trace JSON.
     */
    private static String modern(String source, TraceDispatch.Capture captured,
            String guestStdin, TraceJob job) {
        ModernTraceSerializer serializer = new ModernTraceSerializer(job.removeMainArgs(),
                job.inlineStrings(),
                job.removeMethodThis(), job.typeStyle(), job.includePrimitiveBits());

        if (captured instanceof TraceDispatch.Chronological chronological) {
            cs1302.tracer.internal.model.modern.Trace trace = serializer.createTrace(source, guestStdin,
                    chronological.snapshots());
            return ModernTraceSerializer.getGson().toJson(trace);
        } else if (captured instanceof TraceDispatch.FinalState finalState) {
            ExecutionSnapshot snapshot = finalState.snapshot();
            cs1302.tracer.internal.model.modern.Trace trace = serializer.createTrace(source, guestStdin, snapshot);
            return ModernTraceSerializer.getGson().toJson(trace);
        } else {
            TraceDispatch.Breakpoints selected = (TraceDispatch.Breakpoints) captured;
            Map<Integer, List<ExecutionSnapshot>> snapshots = selected.snapshots();
            if (selected.accumulated()) {
                cs1302.tracer.internal.model.modern.Trace trace = serializer.createBreakpointsTrace(
                        source, guestStdin,
                        snapshots);
                return ModernTraceSerializer.getGson().toJson(trace);
            } else {
                Map<Integer, Object> latestSnapshots = new LinkedHashMap<>();
                for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshots.entrySet()) {
                    List<ExecutionSnapshot> list = e.getValue();
                    if (list.size() == 1) {
                        latestSnapshots.put(e.getKey(), list.get(0));
                    } else {
                        latestSnapshots.put(e.getKey(), list);
                    } // if
                } // for
                cs1302.tracer.internal.model.modern.Trace trace = serializer.createBreakpointsTrace(
                        source, guestStdin,
                        latestSnapshots);
                return ModernTraceSerializer.getGson().toJson(trace);
            } // if
        } // if
    } // modern

    /**
     * Renders ordinary Python Tutor JSON.
     *
     * @param source Source string.
     * @param captured Captured execution states.
     * @param guestStdin Standard input string for guest process.
     * @param job Resolved presentation choices.
     * @param pretty Whether to indent supported output shapes.
     * @return Ordinary trace JSON.
     */
    private static String pytutor(String source, TraceDispatch.Capture captured,
            String guestStdin, TraceJob job, boolean pretty) {
        PyTutorSerializer serializer = new PyTutorSerializer(job.removeMainArgs(),
                job.inlineStrings(),
                job.removeMethodThis(), job.typeStyle(), job.includePrimitiveBits());

        if (captured instanceof TraceDispatch.Chronological chronological) {
            PyTutorTrace trace = serializer.createTrace(source, guestStdin,
                    chronological.snapshots());
            return PyTutorSerializer.getGson(pretty).toJson(trace);
        } else if (captured instanceof TraceDispatch.FinalState finalState) {
            ExecutionSnapshot snapshot = finalState.snapshot();
            String pyTutorSnapshot = serializer.serialize(source, guestStdin, snapshot, pretty);
            return pyTutorSnapshot;
        } else {
            SourceMetadata metadata = SourceMetadata.from(source);
            TraceDispatch.Breakpoints selected = (TraceDispatch.Breakpoints) captured;
            Map<Integer, List<ExecutionSnapshot>> snapshots = selected.snapshots();
            if (selected.accumulated()) {
                Map<Integer, List<PyTutorTrace>> pyTutorSnapshots = snapshots.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                                .map(s -> serializer.createTrace(source, guestStdin, s, metadata)).toList()));
                return PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots);
            } else {
                Map<Integer, Object> pyTutorSnapshots = new LinkedHashMap<>();
                for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshots.entrySet()) {
                    List<ExecutionSnapshot> list = e.getValue();
                    if (list.size() == 1) {
                        pyTutorSnapshots.put(e.getKey(),
                                serializer.createTrace(source, guestStdin, list.get(0), metadata));
                    } else {
                        pyTutorSnapshots.put(e.getKey(), list.stream()
                                .map(s -> serializer.createTrace(source, guestStdin, s, metadata)).toList());
                    } // if
                } // for
                return PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots);
            } // if
        } // if
    } // pytutor

} // TraceRendering
