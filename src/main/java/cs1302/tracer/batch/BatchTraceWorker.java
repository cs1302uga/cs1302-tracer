package cs1302.tracer.batch;

import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.App;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.CompilationHelper.SourceFile;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.JobOptions;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.execution.TraceSession;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.TypeStyle;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.BreakpointSpec;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.PersistentGuestSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Worker managing a persistent guest JVM session and executing batch trace jobs sequentially.
 */
public final class BatchTraceWorker implements AutoCloseable {

    private final int maxJobsPerWorker;
    private PersistentGuestSession session;

    /**
     * Constructs a worker with specified job recycling threshold.
     *
     * @param maxJobsPerWorker Maximum jobs before the guest process is recycled.
     */
    public BatchTraceWorker(int maxJobsPerWorker) {
        this.maxJobsPerWorker = Math.max(1, maxJobsPerWorker);
    } // BatchTraceWorker

    /**
     * Ensures an active, healthy persistent guest session.
     *
     * @throws Exception If spawning the guest JVM fails.
     */
    private synchronized void ensureSession() throws Exception {
        if (session == null || !session.isAlive()
                || session.completedJobCount() >= maxJobsPerWorker) {
            close();
            session = PersistentGuestSession.create();
        } // if
    } // ensureSession

    /**
     * Executes a single trace job request and produces a correlated response.
     *
     * @param req Job request specification.
     * @return Batch job response containing execution or error results.
     */
    public synchronized BatchJobResponse execute(BatchJobRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("BatchJobRequest cannot be null");
        } // if

        TraceFormat format;
        TypeStyle typeStyle;
        try {
            format = req.resolveFormat();
            typeStyle = req.resolveTypeStyle();
        } catch (IllegalArgumentException valErr) {
            TraceResult errResult = TraceResult.failed(
                    "pytutor", "validation", valErr.getMessage());
            return new BatchJobResponse(req.id(), errResult);
        } // try

        TraceLimits limits = req.resolveLimits();
        InspectionPolicy inspection = req.inspection() != null
                ? req.inspection() : InspectionPolicy.TRUSTED;
        boolean allBps = Boolean.TRUE.equals(req.allBreakpoints());
        boolean accBps = Boolean.TRUE.equals(req.accumulateBreakpoints());

        try {
            ensureSession();
        } catch (Throwable launchErr) {
            restoreInterruptIfInterrupted(launchErr);
            TraceResult errResult = TraceResult.failed(
                    format.name().toLowerCase(Locale.ROOT), "tracer",
                    "Failed to launch persistent guest session: " + launchErr.getMessage());
            return new BatchJobResponse(req.id(), errResult);
        } // try

        try (TraceSession traceSession = new TraceSession(
                limits, inspection, allBps || accBps, false)) {
            try {
                traceSession.attach(session.process(), false);
                Object payload = performTrace(req, traceSession, allBps, accBps, format, typeStyle);
                TraceResult result = traceSession.result(
                        format.name().toLowerCase(Locale.ROOT), payload, null);
                return new BatchJobResponse(req.id(), result);
            } catch (Throwable caught) {
                restoreInterruptIfInterrupted(caught);
                TraceResult result = traceSession.result(
                        format.name().toLowerCase(Locale.ROOT), null, caught);
                return new BatchJobResponse(req.id(), result);
            } // try
        } // try
    } // execute

    /**
     * Performs compilation and dispatches tracing to the persistent guest session.
     *
     * @param req Batch request.
     * @param traceSession Active tracing session.
     * @param allBps Chronological flag.
     * @param accBps Accumulate flag.
     * @param format Output format.
     * @param typeStyle Type styling.
     * @return Serialized payload.
     * @throws Exception On compilation or execution error.
     */
    private Object performTrace(
            BatchJobRequest req,
            TraceSession traceSession,
            boolean allBps,
            boolean accBps,
            TraceFormat format,
            TypeStyle typeStyle) throws Exception {
        if (req.source() == null || req.source().isBlank()) {
            throw new IllegalArgumentException("Source code cannot be empty");
        } // if

        TraceLimits limits = req.resolveLimits();
        traceSession.enforce(
                (long) req.source().getBytes(StandardCharsets.UTF_8).length,
                limits.sourceBytes(), "source_limit");
        long files = CompilationHelper.DELIMITER_PATTERN.matcher(req.source()).results().count();
        traceSession.enforce(Math.max(1, files), limits.sourceFiles(), "source_file_limit");

        traceSession.phase("compile");
        List<SourceFile> sourceFiles = CompilationHelper.parseMultiFileStream(req.source());
        SourceFile entryFile = CompilationHelper.findEntryPoint(sourceFiles);
        InspectionPolicy inspection = req.inspection() != null
                ? req.inspection() : InspectionPolicy.TRUSTED;
        Optional<Path> sourceRoot = inspection == InspectionPolicy.FIELDS
                ? Optional.empty()
                : CompilationHelper.findSourceRoot(entryFile.ast(), Optional.empty());

        Object payload;
        try (CompilationResult compiled = CompilationHelper.compile(req.source(), sourceRoot)) {
            Optional<Path> parserRoot = inspection == InspectionPolicy.FIELDS
                    ? Optional.empty()
                    : (sourceRoot.isPresent() ? sourceRoot : Optional.of(compiled.classPath()));
            List<CompilationUnit> units = App.discoverAllCompilationUnits(
                    sourceFiles, sourceRoot, parserRoot);

            traceSession.phase("trace");
            String stdin = req.stdin() != null ? req.stdin() : "";
            List<BreakpointSpec> specs = JobOptions.parseBreakpoints(req.breakpoints());
            if (allBps) {
                List<BreakpointSpec> effectiveSpecs = specs;
                if (specs.isEmpty()) {
                    Collection<Integer> lines = DebugTraceHelper.getValidBreakpointLines(compiled);
                    effectiveSpecs = lines.stream().map(BreakpointSpec::of).toList();
                } // if
                List<ExecutionSnapshot> snapshots =
                        session.traceChronologicalWithSpecs(compiled, effectiveSpecs, units, stdin);
                payload = serializeChronologicalPayload(req, format, typeStyle, snapshots);
            } else {
                Map<Integer, List<ExecutionSnapshot>> snapshotsMap =
                        session.traceWithSpecs(compiled, specs, units, stdin, accBps);
                payload = serializeTargetedPayload(req, format, typeStyle, snapshotsMap, accBps);
            } // if
        } // try
        return payload;
    } // performTrace

    /**
     * Serializes chronological execution snapshots into the selected output format.
     *
     * @param req Job request.
     * @param format Output format.
     * @param typeStyle Type styling.
     * @param snapshots Captured snapshots.
     * @return Formatted trace model.
     */
    private Object serializeChronologicalPayload(
            BatchJobRequest req,
            TraceFormat format,
            TypeStyle typeStyle,
            List<ExecutionSnapshot> snapshots) {
        boolean removeMainArgs = Boolean.TRUE.equals(req.removeMainArgs());
        boolean inlineStrings = Boolean.TRUE.equals(req.inlineStrings());
        boolean removeMethodThis = Boolean.TRUE.equals(req.removeMethodThis());
        String stdin = req.stdin() != null ? req.stdin() : "";

        if (format == TraceFormat.MODERN) {
            return new ModernTraceSerializer(
                    removeMainArgs, inlineStrings, removeMethodThis, typeStyle)
                    .createTrace(req.source(), stdin, snapshots);
        } // if
        return new PyTutorSerializer(
                removeMainArgs, inlineStrings, removeMethodThis, typeStyle)
                .createTrace(req.source(), stdin, snapshots);
    } // serializeChronologicalPayload

    /**
     * Serializes line-targeted execution snapshot maps into the selected output format.
     *
     * @param req Job request.
     * @param format Output format.
     * @param typeStyle Type styling.
     * @param snapshotsMap Captured snapshot map.
     * @param accBps Accumulate flag.
     * @return Formatted trace model.
     */
    private Object serializeTargetedPayload(
            BatchJobRequest req,
            TraceFormat format,
            TypeStyle typeStyle,
            Map<Integer, List<ExecutionSnapshot>> snapshotsMap,
            boolean accBps) {
        boolean removeMainArgs = Boolean.TRUE.equals(req.removeMainArgs());
        boolean inlineStrings = Boolean.TRUE.equals(req.inlineStrings());
        boolean removeMethodThis = Boolean.TRUE.equals(req.removeMethodThis());
        String stdin = req.stdin() != null ? req.stdin() : "";

        if (format == TraceFormat.MODERN) {
            ModernTraceSerializer serializer = new ModernTraceSerializer(
                    removeMainArgs, inlineStrings, removeMethodThis, typeStyle);
            if (accBps) {
                return serializer.createBreakpointsTrace(req.source(), stdin, snapshotsMap);
            } else {
                Map<Integer, Object> latestSnapshots = new LinkedHashMap<>();
                for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshotsMap.entrySet()) {
                    List<ExecutionSnapshot> list = e.getValue();
                    latestSnapshots.put(e.getKey(), list.get(0));
                } // for
                return serializer.createBreakpointsTrace(req.source(), stdin, latestSnapshots);
            } // if
        } // if

        PyTutorSerializer serializer = new PyTutorSerializer(
                removeMainArgs, inlineStrings, removeMethodThis, typeStyle);
        if (accBps) {
            Map<Integer, List<PyTutorTrace>> pyTutorSnapshots = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshotsMap.entrySet()) {
                pyTutorSnapshots.put(e.getKey(), e.getValue().stream()
                        .map(s -> serializer.createTrace(req.source(), stdin, s))
                        .toList());
            } // for
            return pyTutorSnapshots;
        } else {
            Map<Integer, Object> pyTutorSnapshots = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshotsMap.entrySet()) {
                List<ExecutionSnapshot> list = e.getValue();
                pyTutorSnapshots.put(e.getKey(), serializer.createTrace(
                        req.source(), stdin, list.get(0)));
            } // for
            return pyTutorSnapshots;
        } // if
    } // serializeTargetedPayload

    /**
     * Restores interrupted status on current thread if caught throwable is an interruption.
     *
     * @param caught Throwable caught during execution.
     */
    static void restoreInterruptIfInterrupted(Throwable caught) {
        if (caught instanceof InterruptedException
                || caught instanceof java.io.InterruptedIOException) {
            Thread.currentThread().interrupt();
        } // if
    } // restoreInterruptIfInterrupted

    @Override
    public synchronized void close() {
        if (session != null) {
            session.close();
            session = null;
        } // if
    } // close
}
