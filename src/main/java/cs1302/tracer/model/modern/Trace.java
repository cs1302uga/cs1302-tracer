package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Root output model for the modern trace format.
 *
 * @param code The original Java source code (or delimited multi-file stream).
 * @param format The trace format identifier ("modern").
 * @param stdin The standard input provided to the program.
 * @param steps The sequential list of trace steps.
 * @param breakpoints Optional mapping of breakpoint line numbers to steps when tracing
 *     specific breakpoints.
 */
public record Trace(
        @SerializedName("code") String code,
        @SerializedName("format") String format,
        @SerializedName("stdin") String stdin,
        @SerializedName("steps") List<Step> steps,
        @SerializedName("breakpoints") Map<Integer, Object> breakpoints) {

    /**
     * Constructs a full sequential trace with stdin.
     *
     * @param code The source code.
     * @param stdin The standard input.
     * @param steps The trace steps.
     */
    public Trace(String code, String stdin, List<Step> steps) {
        this(code, "modern", stdin == null ? "" : stdin, steps, null);
    } // Trace

    /**
     * Constructs a breakpoint-mapped trace with stdin.
     *
     * @param code The source code.
     * @param stdin The standard input.
     * @param breakpoints The breakpoints map.
     */
    public Trace(String code, String stdin, Map<Integer, Object> breakpoints) {
        this(code, "modern", stdin == null ? "" : stdin, null, breakpoints);
    } // Trace

    /**
     * Constructs a full sequential trace defaulting to empty stdin.
     *
     * @param code The source code.
     * @param steps The trace steps.
     */
    public Trace(String code, List<Step> steps) {
        this(code, "", steps);
    } // Trace

    /**
     * Constructs a breakpoint-mapped trace defaulting to empty stdin.
     *
     * @param code The source code.
     * @param breakpoints The breakpoints map.
     */
    public Trace(String code, Map<Integer, Object> breakpoints) {
        this(code, "", breakpoints);
    } // Trace
} // Trace
