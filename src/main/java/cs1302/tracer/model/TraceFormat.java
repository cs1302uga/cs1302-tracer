package cs1302.tracer.model;

/**
 * Output format options for code execution tracing.
 */
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
