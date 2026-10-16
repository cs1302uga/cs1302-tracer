package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Output format options for code execution tracing.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum TraceFormat {
    /** Standard Online Python Tutor JSON trace format. */
    PYTUTOR,

    /** Modern clean JSON trace format with typed objects and reference modeling. */
    MODERN;

    @Override
    public String toString() {
        return name().toLowerCase();
    } // toString
} // TraceFormat
