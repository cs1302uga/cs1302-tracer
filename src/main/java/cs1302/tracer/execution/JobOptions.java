package cs1302.tracer.execution;

import cs1302.tracer.trace.BreakpointSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import picocli.CommandLine.Option;

/** CLI settings for tracing budgets and the opt-in versioned result envelope. */
public class JobOptions {

    @Option(names = "--result-envelope", description = "Emit versioned job status and trace JSON.")
    public boolean envelope;
    @Option(names = "--unlimited",
            description = "Disable default budgets; explicit limits still apply.")
    public boolean unlimited;
    @Option(names = "--timeout-ms", 
            description = "Tracing deadline in milliseconds; 0 is unlimited.")
    Long timeoutMillis;
    @Option(names = "--max-snapshots", description = "Maximum captured snapshots; 0 is unlimited.")
    Long snapshots;
    @Option(names = "--max-output-bytes", description = "Guest bytes per stream; 0 is unlimited.")
    Long outputBytes;
    @Option(names = "--max-heap-objects", description = "Objects per snapshot; 0 is unlimited.")
    Long heapObjects;
    @Option(names = "--max-elements", 
            description = "Inspected elements per snapshot; 0 is unlimited.")
    Long elements;
    @Option(names = "--max-trace-bytes", description = "Accounted snapshot bytes; 0 is unlimited.")
    Long traceBytes;
    @Option(names = "--max-source-bytes", description = "UTF-8 source bytes; 0 is unlimited.")
    public Long sourceBytes;
    @Option(names = "--max-source-files", description = "Streamed source files; 0 is unlimited.")
    Long sourceFiles;
    @Option(names = "--inspection", defaultValue = "TRUSTED",
            description = "Inspection policy: ${COMPLETION-CANDIDATES}; FIELDS invokes no methods.")
    public InspectionPolicy inspection = InspectionPolicy.TRUSTED;

    /** Configured breakpoint target specifications. */
    public List<String> breakpoints;

    /**
     * Sets whether enum hash codes should not be evaluated.
     * @param noEval True to disable enum hash evaluation.
     */
    @Option(names = "--no-eval-enum-hash",
            description = "Do not evaluate lazy enum hash codes when capturing snapshots.")
    void setNoEvalEnumHash(boolean noEval) {
        this.evalEnumHash = !noEval;
    } // setNoEvalEnumHash

    /**
     * Sets whether enum hash codes should be evaluated.
     * @param eval True to enable enum hash evaluation.
     */
    @Option(names = "--eval-enum-hash",
            description = "Evaluate lazy enum hash codes when capturing snapshots.")
    void setEvalEnumHash(boolean eval) {
        this.evalEnumHash = eval;
    } // setEvalEnumHash

    public boolean evalEnumHash = true;

    /** Constructs default trusted options. */
    public JobOptions() {} // JobOptions

    /**
     * Parses a collection of raw breakpoint strings into BreakpointSpec objects.
     *
     * @param raw Raw breakpoint strings.
     * @return List of parsed BreakpointSpec objects, or empty list if null.
     */
    public static List<BreakpointSpec> parseBreakpoints(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        } // if
        List<BreakpointSpec> specs = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            } // if
            for (String part : item.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    specs.add(BreakpointSpec.parse(trimmed));
                } // if
            } // for
        } // for
        return List.copyOf(specs);
    } // parseBreakpoints

    /**
     * Returns the parsed breakpoint specifications for these options.
     *
     * @return List of BreakpointSpec instances.
     */
    public List<BreakpointSpec> breakpointSpecs() {
        return parseBreakpoints(breakpoints);
    } // breakpointSpecs

    /**
     * Validates and returns the selected budgets.
     * @return Effective limits.
     */
    public TraceLimits limits() {
        TraceLimits defaults = envelope || unlimited
                ? TraceLimits.unlimited() : TraceLimits.instructorDefaults();
        return new TraceLimits(select(timeoutMillis, defaults.timeoutMillis()),
                select(snapshots, defaults.snapshots()),
                select(outputBytes, defaults.outputBytes()),
                select(heapObjects, defaults.heapObjects()), select(elements, defaults.elements()),
                select(traceBytes, defaults.traceBytes()),
                select(sourceBytes, defaults.sourceBytes()),
                select(sourceFiles, defaults.sourceFiles()));
    } // limits

    /**
     * Selects an explicit value, including zero, before applying a default.
     * @param value Explicit setting, or null.
     * @param fallback Default setting.
     * @return Effective setting.
     */
    private static long select(Long value, long fallback) {
        return value == null ? fallback : value;
    } // select
} // JobOptions
