package cs1302.tracer.execution;

import picocli.CommandLine.Option;

/** Opt-in CLI settings for bounded tracing and the versioned result envelope. */
public class JobOptions {

    @Option(names = "--result-envelope", description = "Emit versioned job status and trace JSON.")
    public boolean envelope;
    @Option(names = "--timeout-ms", 
            description = "Tracing deadline in milliseconds; 0 is unlimited.")
    long timeoutMillis;
    @Option(names = "--max-snapshots", description = "Maximum captured snapshots; 0 is unlimited.")
    long snapshots;
    @Option(names = "--max-output-bytes", description = "Guest bytes per stream; 0 is unlimited.")
    long outputBytes;
    @Option(names = "--max-heap-objects", description = "Objects per snapshot; 0 is unlimited.")
    long heapObjects;
    @Option(names = "--max-elements", 
            description = "Inspected elements per snapshot; 0 is unlimited.")
    long elements;
    @Option(names = "--max-trace-bytes", description = "Accounted snapshot bytes; 0 is unlimited.")
    long traceBytes;
    @Option(names = "--max-source-bytes", description = "UTF-8 source bytes; 0 is unlimited.")
    public long sourceBytes;
    @Option(names = "--max-source-files", description = "Streamed source files; 0 is unlimited.")
    long sourceFiles;
    @Option(names = "--inspection", defaultValue = "TRUSTED",
            description = "Inspection policy: ${COMPLETION-CANDIDATES}; FIELDS invokes no methods.")
    public InspectionPolicy inspection = InspectionPolicy.TRUSTED;

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
     * Validates and returns the selected budgets.
     * @return Effective limits.
     */
    public TraceLimits limits() {
        return new TraceLimits(timeoutMillis, snapshots, outputBytes, heapObjects,
                elements, traceBytes, sourceBytes, sourceFiles);
    } // limits
} // JobOptions
