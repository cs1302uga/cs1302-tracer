package cs1302.tracer.execution;

/**
 * Trace budgets. Zero means unlimited; byte counts use bytes, time uses milliseconds.
 * @param timeoutMillis Elapsed tracing deadline, excluding compilation.
 * @param snapshots Maximum completed snapshots captured, including replaced snapshots.
 * @param outputBytes Maximum retained bytes per guest output stream.
 * @param heapObjects Maximum distinct reachable objects per snapshot.
 * @param elements Maximum inspected fields/array elements/string characters per snapshot.
 * @param traceBytes Maximum accounted retained snapshot bytes.
 * @param sourceBytes Maximum UTF-8 source bytes.
 * @param sourceFiles Maximum streamed source files.
 * @param threads Maximum live application threads.
 * @param frames Maximum total frames per snapshot.
 * @param snapshotBytes Maximum accounted bytes for one snapshot.
 */
public record TraceLimits(long timeoutMillis, long snapshots, long outputBytes,
        long heapObjects, long elements, long traceBytes, long sourceBytes, long sourceFiles,
        long threads, long frames, long snapshotBytes) {

    /**
     * Constructs the original budgets with unlimited thread-specific ceilings.
     * @param timeoutMillis Deadline.
     * @param snapshots Snapshot count.
     * @param outputBytes Output bytes.
     * @param heapObjects Heap objects.
     * @param elements Inspected elements.
     * @param traceBytes Retained bytes.
     * @param sourceBytes Source bytes.
     * @param sourceFiles Source files.
     */
    public TraceLimits(long timeoutMillis, long snapshots, long outputBytes, long heapObjects,
            long elements, long traceBytes, long sourceBytes, long sourceFiles) {
        this(timeoutMillis, snapshots, outputBytes, heapObjects, elements, traceBytes,
                sourceBytes, sourceFiles, 0, 0, 0);
    } // TraceLimits

    /** Validates budgets before any work starts. */
    public TraceLimits {
        if (timeoutMillis < 0 || snapshots < 0 || outputBytes < 0 || heapObjects < 0
                || elements < 0 || traceBytes < 0 || sourceBytes < 0 || sourceFiles < 0
                || threads < 0 || frames < 0 || snapshotBytes < 0) {
            throw new IllegalArgumentException("Trace limits must be nonnegative; 0 is unlimited");
        } // if
        if (timeoutMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Timeout is too large");
        } // if
    } // TraceLimits

    /**
     * Returns the ordinary instructor CLI policy.
     * @return Finite budgets for interactive examples.
     */
    public static TraceLimits instructorDefaults() {
        return new TraceLimits(10_000, 10_000, 1_048_576, 10_000,
                100_000, 67_108_864, 1_048_576, 128, 64, 4096, 8_388_608);
    } // instructorDefaults

    /**
     * Returns the trusted unlimited policy.
     * @return Unlimited budgets.
     */
    public static TraceLimits unlimited() {
        return new TraceLimits(0, 0, 0, 0, 0, 0, 0, 0);
    } // unlimited
} // TraceLimits
