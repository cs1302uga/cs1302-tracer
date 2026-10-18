package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Trace payload formats for code execution tracing.
 * {@link TraceRequest#TraceRequest(String)} defaults to {@link #PYTUTOR}; select {@link #MODERN}
 * for multithread capture. A result envelope's status and diagnostics are separate from the
 * format-specific trace payload; changing format does not remove that envelope.
 *
 * @see TraceRequest
 * @see TraceOutcome
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum TraceFormat {

    /**
     * Python Tutor-compatible trace JSON with the tracer's documented metadata extensions.
     * Supports single-stack capture and the {@link TraceRequest#inlineStrings()} presentation option.
     */
    PYTUTOR,

    /**
     * Modern trace JSON with explicit stack, heap, type, and reference records.
     * Supports both single-stack and multithread capture. Strings remain heap entries even when
     * {@link TraceRequest#inlineStrings()} is true.
     */
    MODERN;

    /**
     * Returns the lowercase format identifier used in result metadata.
     *
     * @return {@code pytutor} or {@code modern}.
     */
    @Override
    public String toString() {
        return name().toLowerCase();
    } // toString

} // TraceFormat
