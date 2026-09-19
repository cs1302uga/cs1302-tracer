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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
    public BatchJobResponse execute(BatchJobRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("BatchJobRequest cannot be null");
        } // if

        TraceFormat format = req.resolveFormat();
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
            traceSession.attach(session.process(), false);
            try {
                List<ExecutionSnapshot> snapshots = performTrace(req, traceSession, allBps, accBps);
                Object payload = serializePayload(req, format, snapshots);
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
     * @return List of captured execution snapshots.
     * @throws Exception On compilation or execution error.
     */
    private List<ExecutionSnapshot> performTrace(
            BatchJobRequest req,
            TraceSession traceSession,
            boolean allBps,
            boolean accBps) throws Exception {
        if (req.source() == null || req.source().isBlank()) {
            throw new IllegalArgumentException("Source code cannot be empty");
        } // if
        traceSession.phase("compile");
        List<SourceFile> sourceFiles = CompilationHelper.parseMultiFileStream(req.source());
        SourceFile entryFile = CompilationHelper.findEntryPoint(sourceFiles);
        Optional<Path> sourceRoot = CompilationHelper.findSourceRoot(
                entryFile.ast(), Optional.empty());

        try (CompilationResult compiled = CompilationHelper.compile(req.source(), sourceRoot)) {
            List<CompilationUnit> units = App.discoverAllCompilationUnits(
                    sourceFiles, sourceRoot, Optional.of(compiled.classPath()));

            traceSession.phase("trace");
            String stdin = req.stdin() != null ? req.stdin() : "";
            List<BreakpointSpec> specs = JobOptions.parseBreakpoints(req.breakpoints());
            dispatchGuestExecution(compiled, specs, units, stdin, allBps, accBps);
            return traceSession.snapshots();
        } // try
    } // performTrace

    /**
     * Dispatches either chronological or line-targeted tracing to the persistent session.
     *
     * @param compiled Compilation result.
     * @param specs Parsed breakpoint specifications.
     * @param units AST compilation units.
     * @param stdin Standard input string.
     * @param allBps Chronological flag.
     * @param accBps Accumulate flag.
     * @throws Exception On trace dispatch failure.
     */
    private void dispatchGuestExecution(
            CompilationResult compiled,
            List<BreakpointSpec> specs,
            List<CompilationUnit> units,
            String stdin,
            boolean allBps,
            boolean accBps) throws Exception {
        if (allBps) {
            List<BreakpointSpec> effectiveSpecs = specs;
            if (specs.isEmpty()) {
                Collection<Integer> lines = DebugTraceHelper.getValidBreakpointLines(compiled);
                effectiveSpecs = lines.stream().map(BreakpointSpec::of).toList();
            } // if
            session.traceChronologicalWithSpecs(compiled, effectiveSpecs, units, stdin);
        } else {
            session.traceWithSpecs(compiled, specs, units, stdin, accBps);
        } // if
    } // dispatchGuestExecution

    /**
     * Serializes execution snapshots into the selected output payload format.
     *
     * @param req Job request.
     * @param format Output format.
     * @param snapshots Captured snapshots.
     * @return Formatted trace model.
     */
    private Object serializePayload(
            BatchJobRequest req,
            TraceFormat format,
            List<ExecutionSnapshot> snapshots) {
        boolean removeMainArgs = Boolean.TRUE.equals(req.removeMainArgs());
        boolean inlineStrings = Boolean.TRUE.equals(req.inlineStrings());
        boolean removeMethodThis = Boolean.TRUE.equals(req.removeMethodThis());
        TypeStyle typeStyle = req.resolveTypeStyle();
        String stdin = req.stdin() != null ? req.stdin() : "";

        if (format == TraceFormat.MODERN) {
            return new ModernTraceSerializer(
                    removeMainArgs, inlineStrings, removeMethodThis, typeStyle)
                    .createTrace(req.source(), stdin, snapshots);
        } // if
        return new PyTutorSerializer(
                removeMainArgs, inlineStrings, removeMethodThis, typeStyle)
                .createTrace(req.source(), stdin, snapshots);
    } // serializePayload

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
