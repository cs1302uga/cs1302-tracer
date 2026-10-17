package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Output format options for code execution tracing.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum TraceFormat {

    /** Python Tutor-compatible trace JSON with the tracer's documented metadata extensions. */
    PYTUTOR,

    /** Modern trace JSON with explicit stack, heap, type, and reference records; supports multithread capture. */
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
