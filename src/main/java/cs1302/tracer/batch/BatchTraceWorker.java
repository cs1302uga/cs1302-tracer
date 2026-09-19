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
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.BreakpointSpec;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.PersistentGuestSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        boolean healthy = false;
        try {
            healthy = session != null && session.isAlive()
                    && session.completedJobCount() < maxJobsPerWorker;
        } catch (Throwable t) {
            healthy = false;
        } // try
        if (!healthy) {
            close();
            PersistentGuestSession created = PersistentGuestSession.create();
            if (!created.isAlive()) {
                created.close();
                throw new IllegalStateException(
                        "Persistent guest session terminated during startup");
            } // if
            session = created;
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

        TraceLimits limits = req.resolveLimits();
        TraceFormat format = TraceFormat.PYTUTOR;
        TypeStyle typeStyle;
        try {
            format = req.resolveFormat();
            typeStyle = req.resolveTypeStyle();
        } catch (IllegalArgumentException valErr) {
            TraceResult errResult = TraceResult.failed(
                    format.name().toLowerCase(Locale.ROOT), "validation",
                    valErr.getMessage(), limits);
            return new BatchJobResponse(req.id(), errResult);
        } // try

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
                    "Failed to launch persistent guest session: " + launchErr.getMessage(),
                    limits);
            return new BatchJobResponse(req.id(), errResult);
        } // try

        try (TraceSession traceSession = new TraceSession(
                limits, inspection, allBps || accBps, true)) {
            try {
                Object payload = performTrace(req, traceSession, allBps, accBps, format, typeStyle);
                if (!session.isAlive() && traceSession.stopReason() == null) {
                    traceSession.stop("guest_exit");
                } // if
                TraceResult result = traceSession.result(
                        format.name().toLowerCase(Locale.ROOT), payload, null);
                return new BatchJobResponse(req.id(), result);
            } catch (Throwable caught) {
                restoreInterruptIfInterrupted(caught);
                Object payload = serializePartialPayload(
                        req, traceSession, format, typeStyle, caught);
                TraceResult result = traceSession.result(
                        format.name().toLowerCase(Locale.ROOT), payload, caught);
                return new BatchJobResponse(req.id(), result);
            } // try
        } // try
    } // execute

    /**
     * Serializes completed snapshots retained after a recoverable tracing failure.
     *
     * @param req Batch request.
     * @param traceSession Active tracing session.
     * @param format Output format.
     * @param typeStyle Type styling.
     * @param failure Original tracing failure.
     * @return Partial trace payload, or null when no snapshot was completed.
     */
    private Object serializePartialPayload(
            BatchJobRequest req,
            TraceSession traceSession,
            TraceFormat format,
            TypeStyle typeStyle,
            Throwable failure) {
        if (!traceSession.traceAvailable() || traceSession.snapshots().isEmpty()) {
            return null;
        } // if
        try {
            return serializeChronologicalPayload(
                    req, format, typeStyle, retainedSnapshots(req, traceSession));
        } catch (RuntimeException serializationFailure) {
            failure.addSuppressed(serializationFailure);
            return null;
        } // try
    } // serializePartialPayload

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
            traceSession.attach(session.process(), false);
            String stdin = req.stdin() != null ? req.stdin() : "";
            if (allBps) {
                List<BreakpointSpec> specs = resolveChronologicalSpecs(req, compiled);
                List<ExecutionSnapshot> snapshots =
                        session.traceChronologicalWithSpecs(compiled, specs, units, true, stdin);
                traceSession.phase("serialize");
                payload = serializeChronologicalPayload(req, format, typeStyle, snapshots);
            } else {
                List<BreakpointSpec> specs = req.breakpoints() == null
                        ? List.of() : JobOptions.parseBreakpoints(req.breakpoints());
                session.traceWithSpecs(compiled, specs, units, stdin, accBps);
                traceSession.phase("serialize");
                payload = serializeChronologicalPayload(
                        req, format, typeStyle, retainedSnapshots(req, traceSession));
            } // if
        } // try
        return payload;
    } // performTrace

    /**
     * Returns retained states in capture order, refreshing final output per targeted location.
     *
     * @param req Job request.
     * @param traceSession Active tracing session.
     * @return Completed states with the session's latest-per-line or accumulation policy.
     */
    private List<ExecutionSnapshot> retainedSnapshots(
            BatchJobRequest req, TraceSession traceSession) {
        List<ExecutionSnapshot> snapshots = new ArrayList<>(traceSession.snapshots());
        if (snapshots.isEmpty() || Boolean.TRUE.equals(req.allBreakpoints())
                || "trace_limit".equals(traceSession.stopReason())) {
            return snapshots;
        } // if
        ExecutionSnapshot last = snapshots.getLast();
        Map<Optional<String>, Set<Long>> updated = new HashMap<>();
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            ExecutionSnapshot snapshot = snapshots.get(i);
            long line = snapshot.stack().isEmpty()
                    ? -1 : snapshot.stack().getLast().methodLine();
            if (updated.computeIfAbsent(snapshot.sourcePath(), key -> new HashSet<>())
                    .add(line)) {
                snapshots.set(i, new ExecutionSnapshot(
                        snapshot.stack(), snapshot.statics(), snapshot.heap(),
                        last.stdoutSlice(), last.stderrSlice(), snapshot.sourcePath(),
                        snapshot.stdinConsumed(), snapshot.stdinOffset()));
            } // if
        } // for
        return snapshots;
    } // retainedSnapshots

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
     * Resolves effective breakpoint specifications for chronological tracing.
     *
     * @param req Batch job request.
     * @param compiled Compilation result.
     * @return Effective breakpoint specifications.
     * @throws Exception If breakpoint lines cannot be determined.
     */
    static List<BreakpointSpec> resolveChronologicalSpecs(
            BatchJobRequest req, CompilationResult compiled) throws Exception {
        List<BreakpointSpec> specs = req.breakpoints() != null
                ? JobOptions.parseBreakpoints(req.breakpoints())
                : List.of();
        if (specs.isEmpty()) {
            return DebugTraceHelper.getValidBreakpointLines(compiled).stream()
                    .map(BreakpointSpec::of).toList();
        } // if
        return specs;
    } // resolveChronologicalSpecs

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

} // BatchTraceWorker
