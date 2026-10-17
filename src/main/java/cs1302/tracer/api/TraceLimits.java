package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Trace budgets. Zero means unlimited; byte counts use bytes, time uses milliseconds.
 * Budgets must be nonnegative, and the timeout must not exceed {@code Long.MAX_VALUE / 1_000_000}.
 * Retained-byte accounting is not a measurement of process memory or a limit on serialized response size.
 *
 * @param timeoutMillis Elapsed deadline from the trace phase, excluding source preparation and reusable guest startup.
 * @param snapshots Maximum completed snapshots captured, including replaced snapshots.
 * @param outputBytes Maximum retained bytes per guest output stream.
 * @param heapObjects Maximum distinct reachable objects per snapshot.
 * @param elements Maximum cumulative inspected fields, array slots, frames/locals, and string storage units
 *     per snapshot.
 * @param traceBytes Maximum accounted retained snapshot bytes.
 * @param sourceBytes Maximum UTF-8 source bytes.
 * @param sourceFiles Maximum delimiter-defined source files; undelimited input counts as one.
 * @param threads Maximum live application threads in multithread capture.
 * @param frames Maximum total frames in multithread capture before filtering application stacks.
 * @param snapshotBytes Maximum accounted bytes for one snapshot.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceLimits(long timeoutMillis, long snapshots, long outputBytes, long heapObjects, long elements,
        long traceBytes, long sourceBytes, long sourceFiles, long threads, long frames, long snapshotBytes) {

    /**
     * Constructs the original eight budgets with unlimited thread, frame, and individual-snapshot ceilings.
     *
     * @param timeoutMillis Deadline.
     * @param snapshots Snapshot count.
     * @param outputBytes Output bytes.
     * @param heapObjects Heap objects.
     * @param elements Inspected elements.
     * @param traceBytes Retained bytes.
     * @param sourceBytes Source bytes.
     * @param sourceFiles Source files.
     * @throws IllegalArgumentException If a budget is negative or the timeout exceeds
     *     {@code Long.MAX_VALUE / 1_000_000} milliseconds.
     */
    public TraceLimits(long timeoutMillis, long snapshots, long outputBytes, long heapObjects, long elements,
            long traceBytes, long sourceBytes, long sourceFiles) {
        this(timeoutMillis, snapshots, outputBytes, heapObjects, elements, traceBytes, sourceBytes, sourceFiles, 0, 0,
                0);
    } // TraceLimits

    /**
     * Validates budgets before any work starts.
     *
     * @throws IllegalArgumentException If a budget is negative or the timeout exceeds
     *     {@code Long.MAX_VALUE / 1_000_000} milliseconds.
     */
    public TraceLimits {
        if (timeoutMillis < 0 || snapshots < 0 || outputBytes < 0 || heapObjects < 0 || elements < 0 || traceBytes < 0
                || sourceBytes < 0 || sourceFiles < 0 || threads < 0 || frames < 0 || snapshotBytes < 0) {
            throw new IllegalArgumentException("Trace limits must be nonnegative; 0 is unlimited");
        } // if
        if (timeoutMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Timeout is too large");
        } // if
    } // TraceLimits

    /**
     * Returns the ordinary instructor CLI policy.
     *
     * @return Finite budgets for interactive examples.
     */
    public static TraceLimits instructorDefaults() {
        return new TraceLimits(10_000, 10_000, 1_048_576, 10_000, 100_000, 67_108_864, 1_048_576, 128, 64, 4096,
                8_388_608);
    } // instructorDefaults

    /**
     * Returns unlimited budgets without selecting an inspection policy.
     *
     * @return Unlimited budgets.
     */
    public static TraceLimits unlimited() {
        return new TraceLimits(0, 0, 0, 0, 0, 0, 0, 0);
    } // unlimited

    /**
     * Creates a builder initialized with {@link #instructorDefaults()}.
     * Use {@code unlimited().toBuilder()} to start with unlimited budgets instead.
     *
     * @return A mutable builder initialized with instructor budgets.
     * @since 4.1.0
     */
    public static Builder builder() {
        return new Builder(instructorDefaults());
    } // builder

    /**
     * Returns a builder initialized from this value.
     *
     * @return An independent mutable builder.
     * @since 4.1.0
     */
    public Builder toBuilder() {
        return new Builder(this);
    } // toBuilder

    /**
     * Mutable, reusable budget construction; not thread-safe.
     * Values are validated by {@link #build()}. Zero continues to mean unlimited for every budget.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        private long timeoutMillis;
        private long snapshots;
        private long outputBytes;
        private long heapObjects;
        private long elements;
        private long traceBytes;
        private long sourceBytes;
        private long sourceFiles;
        private long threads;
        private long frames;
        private long snapshotBytes;

        /**
         * Copies every setting from an existing value.
         *
         * @param original Initial settings.
         */
        private Builder(TraceLimits original) {
            this.timeoutMillis = original.timeoutMillis();
            this.snapshots = original.snapshots();
            this.outputBytes = original.outputBytes();
            this.heapObjects = original.heapObjects();
            this.elements = original.elements();
            this.traceBytes = original.traceBytes();
            this.sourceBytes = original.sourceBytes();
            this.sourceFiles = original.sourceFiles();
            this.threads = original.threads();
            this.frames = original.frames();
            this.snapshotBytes = original.snapshotBytes();
        } // Builder

        /**
         * Sets the timeoutMillis setting.
         *
         * @param timeoutMillis Deadline in milliseconds from the trace phase, excluding source preparation
         *     and reusable guest startup.
         * @return This builder.
         */
        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        } // timeoutMillis

        /**
         * Sets the snapshots setting.
         *
         * @param snapshots Maximum completed snapshots captured, including replaced snapshots.
         * @return This builder.
         */
        public Builder snapshots(long snapshots) {
            this.snapshots = snapshots;
            return this;
        } // snapshots

        /**
         * Sets the outputBytes setting.
         *
         * @param outputBytes Maximum retained bytes per guest output stream.
         * @return This builder.
         */
        public Builder outputBytes(long outputBytes) {
            this.outputBytes = outputBytes;
            return this;
        } // outputBytes

        /**
         * Sets the heapObjects setting.
         *
         * @param heapObjects Maximum distinct reachable objects per snapshot.
         * @return This builder.
         */
        public Builder heapObjects(long heapObjects) {
            this.heapObjects = heapObjects;
            return this;
        } // heapObjects

        /**
         * Sets the elements setting.
         *
         * @param elements Maximum cumulative inspected fields, array slots, frames/locals, and string storage
         *     units per snapshot; repeated inspection can count more than once.
         * @return This builder.
         */
        public Builder elements(long elements) {
            this.elements = elements;
            return this;
        } // elements

        /**
         * Sets the traceBytes setting.
         *
         * @param traceBytes Maximum accounted retained snapshot bytes.
         * @return This builder.
         */
        public Builder traceBytes(long traceBytes) {
            this.traceBytes = traceBytes;
            return this;
        } // traceBytes

        /**
         * Sets the sourceBytes setting.
         *
         * @param sourceBytes Maximum UTF-8 source bytes.
         * @return This builder.
         */
        public Builder sourceBytes(long sourceBytes) {
            this.sourceBytes = sourceBytes;
            return this;
        } // sourceBytes

        /**
         * Sets the sourceFiles setting.
         *
         * @param sourceFiles Maximum delimiter-defined source files; undelimited input counts as one.
         * @return This builder.
         */
        public Builder sourceFiles(long sourceFiles) {
            this.sourceFiles = sourceFiles;
            return this;
        } // sourceFiles

        /**
         * Sets the threads setting.
         *
         * @param threads Maximum live application threads in multithread capture.
         * @return This builder.
         */
        public Builder threads(long threads) {
            this.threads = threads;
            return this;
        } // threads

        /**
         * Sets the frames setting.
         *
         * @param frames Maximum total frames in multithread capture before filtering application stacks.
         * @return This builder.
         */
        public Builder frames(long frames) {
            this.frames = frames;
            return this;
        } // frames

        /**
         * Sets the snapshotBytes setting.
         *
         * @param snapshotBytes Maximum accounted bytes for one snapshot.
         * @return This builder.
         */
        public Builder snapshotBytes(long snapshotBytes) {
            this.snapshotBytes = snapshotBytes;
            return this;
        } // snapshotBytes

        /**
         * Builds budgets using the existing constructor's validation.
         *
         * @return A new set of trace budgets.
         * @throws IllegalArgumentException If a budget is negative or the timeout is too large.
         */
        public TraceLimits build() {
            return new TraceLimits(this.timeoutMillis, this.snapshots, this.outputBytes, this.heapObjects,
                    this.elements, this.traceBytes, this.sourceBytes, this.sourceFiles, this.threads,
                    this.frames, this.snapshotBytes);
        } // build

    } // Builder

} // TraceLimits
