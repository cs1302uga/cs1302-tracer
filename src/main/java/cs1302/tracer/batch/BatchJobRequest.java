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
        InspectionPolicy inspection) {

    /**
     * Resolves the trace format, defaulting to PYTUTOR if not specified.
     *
     * @return Resolved TraceFormat.
     */
    public TraceFormat resolveFormat() {
        if (format != null && format.equalsIgnoreCase("modern")) {
            return TraceFormat.MODERN;
        } // if
        return TraceFormat.PYTUTOR;
    } // resolveFormat

    /**
     * Resolves the type style, defaulting to FQN if not specified.
     *
     * @return Resolved TypeStyle.
     */
    public TypeStyle resolveTypeStyle() {
        if (typeStyle != null && typeStyle.equalsIgnoreCase("simple")) {
            return TypeStyle.SIMPLE;
        } // if
        return TypeStyle.FQN;
    } // resolveTypeStyle

    /**
     * Resolves the effective TraceLimits, defaulting to unlimited if omitted.
     *
     * @return Resolved TraceLimits.
     */
    public TraceLimits resolveLimits() {
        return limits != null ? limits : TraceLimits.unlimited();
    } // resolveLimits
}
