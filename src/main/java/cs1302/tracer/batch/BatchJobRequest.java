package cs1302.tracer.batch;

import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.TypeStyle;
import java.util.List;

/**
 * Represents a single batch tracing job request parsed from an NDJSON line.
 *
 * @param id Unique job correlation identifier.
 * @param source Java source code (single or multi-file delimited).
 * @param format Output format (pytutor or modern).
 * @param stdin Optional guest standard input string.
 * @param breakpoints Optional list of breakpoint strings.
 * @param allBreakpoints True to trace all breakpoints chronologically.
 * @param accumulateBreakpoints True to retain all hits per breakpoint.
 * @param removeMainArgs True to omit main method args.
 * @param inlineStrings True to inline String fields.
 * @param removeMethodThis True to omit this reference in methods.
 * @param typeStyle Type styling (fqn or compact).
 * @param limits Optional resource limits.
 * @param inspection Optional inspection policy.
 * @param multithread Enable chronological platform-thread capture in modern format.
 */
public record BatchJobRequest(
        String id,
        String source,
        String format,
        String stdin,
        List<String> breakpoints,
        Boolean allBreakpoints,
        Boolean accumulateBreakpoints,
        Boolean removeMainArgs,
        Boolean inlineStrings,
        Boolean removeMethodThis,
        String typeStyle,
        TraceLimits limits,
        InspectionPolicy inspection,
        Boolean multithread) {

    /**
     * Constructs a request using the original single-stack contract.
     * @param id Correlation ID.
     * @param source Source text.
     * @param format Trace format.
     * @param stdin Standard input.
     * @param breakpoints Breakpoint specifications.
     * @param allBreakpoints Chronological capture.
     * @param accumulateBreakpoints Retain breakpoint hits.
     * @param removeMainArgs Omit main arguments.
     * @param inlineStrings Inline strings.
     * @param removeMethodThis Omit this references.
     * @param typeStyle Type styling.
     * @param limits Job limits.
     * @param inspection Inspection policy.
     */
    public BatchJobRequest(String id, String source, String format, String stdin,
            List<String> breakpoints, Boolean allBreakpoints, Boolean accumulateBreakpoints,
            Boolean removeMainArgs, Boolean inlineStrings, Boolean removeMethodThis,
            String typeStyle, TraceLimits limits, InspectionPolicy inspection) {
        this(id, source, format, stdin, breakpoints, allBreakpoints, accumulateBreakpoints,
                removeMainArgs, inlineStrings, removeMethodThis, typeStyle,
                limits, inspection, false);
    } // BatchJobRequest

    /**
     * Resolves the trace format, defaulting to PYTUTOR if not specified.
     *
     * @return Resolved TraceFormat.
     * @throws IllegalArgumentException If format is unsupported.
     */
    public TraceFormat resolveFormat() {
        if (format == null || format.isBlank()) {
            return TraceFormat.PYTUTOR;
        } // if
        if (format.equalsIgnoreCase("modern")) {
            return TraceFormat.MODERN;
        } // if
        if (format.equalsIgnoreCase("pytutor")) {
            return TraceFormat.PYTUTOR;
        } // if
        throw new IllegalArgumentException("Unsupported trace format: " + format);
    } // resolveFormat

    /**
     * Resolves the type style, defaulting to FQN if not specified.
     *
     * @return Resolved TypeStyle.
     * @throws IllegalArgumentException If typeStyle is unsupported.
     */
    public TypeStyle resolveTypeStyle() {
        if (typeStyle == null || typeStyle.isBlank()) {
            return TypeStyle.FQN;
        } // if
        if (typeStyle.equalsIgnoreCase("simple")) {
            return TypeStyle.SIMPLE;
        } // if
        if (typeStyle.equalsIgnoreCase("fqn")) {
            return TypeStyle.FQN;
        } // if
        throw new IllegalArgumentException("Unsupported type style: " + typeStyle);
    } // resolveTypeStyle

    /**
     * Resolves the effective TraceLimits, defaulting to unlimited if omitted.
     *
     * @return Resolved TraceLimits.
     */
    public TraceLimits resolveLimits() {
        return limits != null ? limits : TraceLimits.unlimited();
    } // resolveLimits
} // BatchJobRequest
