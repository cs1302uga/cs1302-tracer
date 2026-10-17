package cs1302.tracer.internal.execution;

import org.apiguardian.api.API;

import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.SubmittedSources;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.util.List;

/**
 * Selects result payloads and rendering-failure precedence under each caller's completion rules.
 * Callers retain execution, cleanup, interruption, and result-classification responsibilities.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class TraceCompletion {

    /** Prevents utility construction. */
    private TraceCompletion() {} // TraceCompletion

    /**
     * An envelope's payload and the failure that must be passed together to result classification.
     *
     * @param payload Rendered trace, or null when unavailable or rendering failed.
     * @param failure Original execution failure, or the replacing rendering failure.
     */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public record Envelope(Object payload, Throwable failure) {} // Envelope

    /**
     * Completes an envelope after source preparation has been released. Available empty history is renderable;
     * a rendering failure replaces an execution failure and changes the phase to serialization.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param supplied Returned snapshots, or null to recover available raw retained history.
     * @param failure Caught execution failure, or null.
     * @return Coherent payload and failure for result classification.
     */
    public static Envelope envelope(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            List<ExecutionSnapshot> supplied, Throwable failure) {
        return envelope(source, stdin, job, session, supplied, failure, TraceRendering::sequence);
    } // envelope

    /**
     * Completes successful worker capture inside the caller's source-preparation scope. Rendering failures
     * propagate so the worker can retry through its recovery path after cleanup.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param captured Completed capture.
     * @return Rendered sequence payload.
     */
    public static Object workerSuccess(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            TraceDispatch.Capture captured) {
        return workerSuccess(source, stdin, job, session, captured, TraceRendering::sequence);
    } // workerSuccess

    /**
     * Recovers a worker payload after execution, rendering, or cleanup failed. Recovery requires available,
     * nonempty raw retained history. A rendering failure is suppressed onto the existing failure.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param failure Original failure, retained for result classification.
     * @return Partial sequence payload, or null when unavailable or rendering failed.
     */
    public static Object workerRecovery(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            Throwable failure) {
        return workerRecovery(source, stdin, job, session, failure, TraceRendering::sequence);
    } // workerRecovery

    /**
     * Applies envelope completion with an isolated rendering seam.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param supplied Returned snapshots, or null for available raw history.
     * @param failure Original failure, or null.
     * @param renderer Sequence renderer.
     * @return Coherent payload and failure.
     */
    static Envelope envelope(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            List<ExecutionSnapshot> supplied, Throwable failure, SequenceRenderer renderer) {
        List<ExecutionSnapshot> snapshots = supplied;
        if (snapshots == null && session.traceAvailable()) {
            snapshots = session.snapshots();
        } // if
        try {
            return new Envelope(snapshots == null ? null : renderer.render(source, stdin, job, snapshots), failure);
        } catch (RuntimeException serializationFailure) {
            session.phase("serialize");
            return new Envelope(null, serializationFailure);
        } // try
    } // envelope

    /**
     * Applies successful worker completion with an isolated rendering seam.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param captured Completed capture.
     * @param renderer Sequence renderer.
     * @return Rendered sequence payload.
     */
    static Object workerSuccess(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            TraceDispatch.Capture captured, SequenceRenderer renderer) {
        if (job.capture().chronological()) {
            List<ExecutionSnapshot> snapshots = ((TraceDispatch.Chronological) captured).snapshots();
            if (job.multithread()) {
                session.finishOutput();
                snapshots = session.snapshots();
            } // if
            session.phase("serialize");
            return renderer.render(source, stdin, job, snapshots);
        } // if
        session.phase("serialize");
        return renderer.render(source, stdin, job, retainedSnapshots(job, session));
    } // workerSuccess

    /**
     * Applies worker recovery with an isolated rendering seam.
     *
     * @param source Submitted source.
     * @param stdin Guest input.
     * @param job Effective tracing choices.
     * @param session Active job session.
     * @param failure Original failure.
     * @param renderer Sequence renderer.
     * @return Partial sequence payload, or null.
     */
    static Object workerRecovery(SubmittedSources source, String stdin, TraceJob job, TraceSession session,
            Throwable failure, SequenceRenderer renderer) {
        if (!session.traceAvailable() || session.snapshots().isEmpty()) {
            return null;
        } // if
        try {
            return renderer.render(source, stdin, job, retainedSnapshots(job, session));
        } catch (RuntimeException serializationFailure) {
            failure.addSuppressed(serializationFailure);
            return null;
        } // try
    } // workerRecovery

    /**
     * Selects worker history while delegating output projection and budget rules to retention.
     *
     * @param job Effective capture intent.
     * @param session Active job session.
     * @return Retained states for the worker's caller profile.
     */
    private static List<ExecutionSnapshot> retainedSnapshots(TraceJob job, TraceSession session) {
        return job.capture().chronological() ? session.snapshots() : session.targetedSnapshots();
    } // retainedSnapshots

    /** Isolates sequence rendering without exposing configurable completion policies to callers. */
    @FunctionalInterface
    interface SequenceRenderer {

        /**
         * Renders selected states using the existing format-specific implementation.
         *
         * @param source Submitted source.
         * @param stdin Guest input.
         * @param job Effective tracing choices.
         * @param snapshots Completed states.
         * @return Sequence payload.
         */
        Object render(SubmittedSources source, String stdin, TraceJob job, List<ExecutionSnapshot> snapshots);

    } // SequenceRenderer

} // TraceCompletion
