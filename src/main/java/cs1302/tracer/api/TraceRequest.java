package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TypeStyle;
import java.util.List;
import java.util.Objects;

/**
 * A self-contained tracing request; multi-file sources use the documented source delimiters.
 *
 * @param source Submitted Java source text.
 * @param stdin Guest standard input.
 * @param format Output format.
 * @param breakpoints Selected breakpoint specifications; an empty list selects main exit.
 * @param chronological Whether to capture all executable lines chronologically.
 * @param accumulate Whether to retain repeated selected breakpoint hits.
 * @param multithread Whether to capture application threads in modern format.
 * @param removeMainArgs Whether to omit the main argument variable.
 * @param inlineStrings Whether to render strings inline.
 * @param removeMethodThis Whether to omit receiver variables.
 * @param typeStyle Type-label presentation.
 * @param limits Resource budgets.
 * @param inspection Guest inspection policy.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceRequest(String source, String stdin, TraceFormat format, List<String> breakpoints,
        boolean chronological, boolean accumulate, boolean multithread, boolean removeMainArgs,
        boolean inlineStrings, boolean removeMethodThis, TypeStyle typeStyle, TraceLimits limits,
        InspectionPolicy inspection) {

    /** Validates required values and detaches breakpoint selection from the caller. */
    public TraceRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(format, "format");
        breakpoints = List.copyOf(breakpoints);
        Objects.requireNonNull(typeStyle, "typeStyle");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(inspection, "inspection");
    } // TraceRequest

    /**
     * Creates a Python Tutor request with finite instructor budgets and trusted inspection.
     *
     * @param source Submitted Java source text.
     */
    public TraceRequest(String source) {
        this(source, "", TraceFormat.PYTUTOR, List.of(), false, false, false, false, false, false,
                TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED);
    } // TraceRequest
} // TraceRequest
