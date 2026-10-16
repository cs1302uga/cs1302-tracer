package cs1302.tracer.internal.batch;

import org.apiguardian.api.API;

import cs1302.tracer.internal.SourcePreparation;
import cs1302.tracer.internal.SourceDelimiters;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.execution.TraceSession;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.trace.TraceDispatch;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.PersistentGuestSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Worker managing a persistent guest JVM session and executing batch trace jobs sequentially.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class BatchTraceWorker implements AutoCloseable {

    private final int maxJobsPerWorker;
    private PersistentGuestSession session;
    private final Callable<PersistentGuestSession> sessionFactory;

    /**
     * Constructs a worker with specified job recycling threshold.
     *
     * @param maxJobsPerWorker Maximum jobs before the guest process is recycled.
     */
    public BatchTraceWorker(int maxJobsPerWorker) {
        this(maxJobsPerWorker, PersistentGuestSession::create);
    } // BatchTraceWorker

    /**
     * Constructs a worker with a supplied guest launcher.
     *
     * @param maxJobsPerWorker Maximum jobs before recycling.
     * @param sessionFactory Launcher used when a new guest is needed.
     */
    BatchTraceWorker(int maxJobsPerWorker, Callable<PersistentGuestSession> sessionFactory) {
        this.maxJobsPerWorker = Math.max(1, maxJobsPerWorker);
        this.sessionFactory = sessionFactory;
    } // BatchTraceWorker

    /**
     * Ensures an active, healthy persistent guest session.
     *
     * @throws Exception If spawning the guest JVM fails.
     */
    private synchronized void ensureSession() throws Exception {
        boolean healthy = false;
        try {
            healthy = session != null && session.isAlive() && session.completedJobCount() < maxJobsPerWorker;
        } catch (Throwable t) {
            healthy = false;
        } // try
        if (!healthy) {
            close();
            PersistentGuestSession created = sessionFactory.call();
            if (!created.isAlive()) {
                created.close();
                throw new IllegalStateException("Persistent guest session terminated during startup");
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
        try {
            format = req.resolveFormat();
            TypeStyle typeStyle = req.resolveTypeStyle();
            TraceJob job = TraceJob.resolve(TraceJob.Profile.NDJSON, format, typeStyle, limits, req.inspection(),
                    req.breakpoints(), Boolean.TRUE.equals(req.allBreakpoints()),
                    Boolean.TRUE.equals(req.accumulateBreakpoints()), Boolean.TRUE.equals(req.multithread()),
                    Boolean.TRUE.equals(req.removeMainArgs()), Boolean.TRUE.equals(req.inlineStrings()),
                    Boolean.TRUE.equals(req.removeMethodThis()), true, Boolean.TRUE.equals(req.includePrimitiveBits()));
            return new BatchJobResponse(req.id(), execute(req.source(), req.stdin(), job));
        } catch (IllegalArgumentException invalid) {
            return new BatchJobResponse(req.id(), TraceResult.failed(format.toString(), "validation",
                    invalid.getMessage(), limits));
        } // try
    } // execute

    /**
     * Executes resolved choices without interpreting a wire request.
     * @param source Submitted source.
     * @param stdin Guest input, or null for empty input.
     * @param job Effective tracing choices.
     * @return Detached execution result model.
     */
    public synchronized TraceResult execute(String source, String stdin, TraceJob job) {
        TraceLimits limits = job.limits();
        TraceFormat format = job.format();
        try {
            job.validateThreadFormat(false);
        } catch (IllegalArgumentException invalid) {
            return TraceResult.failed(format.toString(), "validation", invalid.getMessage(), limits);
        } // try
        String input = stdin == null ? "" : stdin;
        try {
            prepareGuest(job);
        } catch (Throwable launchErr) {
            restoreInterruptIfInterrupted(launchErr);
            TraceResult errResult = TraceResult.failed(format.name().toLowerCase(Locale.ROOT), "tracer",
                    "Failed to launch persistent guest session: " + launchErr.getMessage(), limits);
            return errResult;
        } // try

        try (TraceSession traceSession = new TraceSession(limits, job.inspection(),
                job.capture().retainsHistory(), job.evalEnumHash())) {
            if (job.multithread()) {
                traceSession.enableMultithread();
            } // if
            try {
                Object payload = performTrace(source, input, job, traceSession);
                if (session != null && !session.isAlive() && traceSession.stopReason() == null) {
                    traceSession.stop("guest_exit");
                } // if
                TraceResult result = traceSession.result(format.name().toLowerCase(Locale.ROOT), payload, null);
                return result;
            } catch (Throwable caught) {
                restoreInterruptIfInterrupted(caught);
                Object payload = serializePartialPayload(source, input, job, traceSession, caught);
                TraceResult result = traceSession.result(format.name().toLowerCase(Locale.ROOT), payload, caught);
                return result;
            } // try
        } // try
    } // execute

    /**
     * Selects a fresh guest for multithread jobs and reusable guests otherwise.
     *
     * @param job Effective job choices.
     * @throws Exception If guest launch fails.
     */
    private void prepareGuest(TraceJob job) throws Exception {
        if (job.multithread()) {
            close();
        } else {
            ensureSession();
        } // if
    } // prepareGuest

    /**
     * Serializes completed snapshots retained after a recoverable tracing failure.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective job choices.
     * @param traceSession Active tracing session.
     * @param failure Original tracing failure.
     * @return Partial trace payload, or null when no snapshot was completed.
     */
    private Object serializePartialPayload(String source, String stdin, TraceJob job, TraceSession traceSession,
            Throwable failure) {
        if (!traceSession.traceAvailable() || traceSession.snapshots().isEmpty()) {
            return null;
        } // if
        try {
            return TraceRendering.sequence(source, stdin, job, retainedSnapshots(job, traceSession));
        } catch (RuntimeException serializationFailure) {
            failure.addSuppressed(serializationFailure);
            return null;
        } // try
    } // serializePartialPayload

    /**
     * Requires the reusable guest established before dispatching a legacy job.
     *
     * @return Initialized guest session.
     */
    private PersistentGuestSession legacyGuest() {
        return java.util.Objects.requireNonNull(session, "Legacy guest must be prepared");
    } // legacyGuest

    /**
     * Performs compilation and dispatches tracing to the persistent guest session.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective job choices.
     * @param traceSession Active tracing session.
     * @return Serialized payload.
     * @throws Exception On compilation or execution error.
     */
    private Object performTrace(String source, String stdin, TraceJob job, TraceSession traceSession) throws Exception {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Source code cannot be empty");
        } // if

        TraceLimits limits = job.limits();
        traceSession.enforce((long) source.getBytes(StandardCharsets.UTF_8).length, limits.sourceBytes(),
                "source_limit");
        long files = SourceDelimiters.scan(source).size();
        traceSession.enforce(Math.max(1, files), limits.sourceFiles(), "source_file_limit");

        traceSession.phase("compile");
        Object payload;
        try (SourcePreparation prepared = SourcePreparation.prepare(
                source, Optional.empty(), job.discovery())) {
            traceSession.phase("trace");
            if (session != null) {
                traceSession.attach(session.process(), false);
            } // if
            TraceDispatch dispatcher = job.multithread() ? TraceDispatch.dedicated()
                    : TraceDispatch.persistent(legacyGuest());
            TraceDispatch.Capture captured = dispatcher.capture(prepared,
                    job.capture().selection(), stdin);
            if (job.capture().chronological()) {
                List<ExecutionSnapshot> snapshots = ((TraceDispatch.Chronological) captured).snapshots();
                if (job.multithread()) {
                    traceSession.finishOutput();
                    snapshots = traceSession.snapshots();
                } // if
                traceSession.phase("serialize");
                payload = TraceRendering.sequence(source, stdin, job, snapshots);
            } else {
                traceSession.phase("serialize");
                payload = TraceRendering.sequence(source, stdin, job, retainedSnapshots(job, traceSession));
            } // if
        } // try
        return payload;
    } // performTrace

    /**
     * Returns retained states in capture order, refreshing final output per targeted location.
     *
     * @param job Effective job choices.
     * @param traceSession Active tracing session.
     * @return Completed states with the session's latest-per-line or accumulation policy.
     */
    private List<ExecutionSnapshot> retainedSnapshots(TraceJob job, TraceSession traceSession) {
        return job.capture().chronological()
                ? traceSession.snapshots() : traceSession.targetedSnapshots();
    } // retainedSnapshots

    /**
     * Restores interrupted status on current thread if caught throwable is an interruption.
     *
     * @param caught Throwable caught during execution.
     */
    static void restoreInterruptIfInterrupted(Throwable caught) {
        if (caught instanceof InterruptedException || caught instanceof java.io.InterruptedIOException) {
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
