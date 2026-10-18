package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Immutable resource budgets for one tracing job. Zero disables the corresponding budget;
 * byte counts use bytes, and time uses milliseconds.
 * Budgets must be nonnegative, and the timeout must not exceed {@code Long.MAX_VALUE / 1_000_000}.
 * Counted resource caps allow usage exactly at the cap; attempting to exceed one stops the job.
 * A nonzero deadline expires when elapsed tracing time reaches the configured duration.
 * Snapshot limits count completed captures even when a later hit replaces an earlier retained state.
 *
 * <p>{@link #builder()} starts with {@link #instructorDefaults()}, matching the budgets of
 * {@link TraceRequest#TraceRequest(String)}. Use {@link #unlimited()} or its {@link #toBuilder()}
 * to disable those defaults. Unlimited budgets do not disable fixed representation checks or
 * provide process isolation. Retained-byte accounting is not a measurement of process memory
 * or a limit on serialized response size.
 *
 * @param timeoutMillis Elapsed deadline from the trace phase, excluding source preparation and reusable guest startup.
 * @param snapshots Maximum completed snapshots captured, including replaced snapshots.
 * @param outputBytes Maximum retained bytes per guest output stream.
 * @param heapObjects Maximum distinct reachable objects per snapshot.
 * @param elements Maximum cumulative inspected fields, array slots, frames/locals, and string storage units
 *     per snapshot.
 * @param traceBytes Maximum accounted snapshot bytes, including retained history and the snapshot
 *     being built; a previous state remains charged until its replacement is admitted.
 * @param sourceBytes Maximum submitted UTF-8 source bytes, excluding discovered neighboring sources
 *     and binary dependencies.
 * @param sourceFiles Maximum delimiter-defined source files; undelimited input counts as one.
 * @param threads Maximum live application threads in multithread capture.
 * @param frames Maximum total frames in multithread capture before filtering application stacks.
 * @param snapshotBytes Maximum accounted bytes for one snapshot.
 * @see TraceRequest
 * @see TraceOutcome
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceLimits(long timeoutMillis, long snapshots, long outputBytes, long heapObjects, long elements,
        long traceBytes, long sourceBytes, long sourceFiles, long threads, long frames, long snapshotBytes) {

    /**
     * Constructs the original eight budgets with unlimited thread, frame, and individual-snapshot ceilings.
     *
     * @param timeoutMillis Trace-phase deadline in milliseconds; zero means unlimited.
     * @param snapshots Maximum completed captures, including replaced snapshots; zero means unlimited.
     * @param outputBytes Maximum retained bytes per guest output stream; zero means unlimited.
     * @param heapObjects Maximum distinct reachable objects per snapshot; zero means unlimited.
     * @param elements Maximum inspected elements per snapshot; zero means unlimited.
     * @param traceBytes Maximum accounted retained snapshot bytes; zero means unlimited.
     * @param sourceBytes Maximum submitted UTF-8 source bytes; zero means unlimited.
     * @param sourceFiles Maximum delimiter-defined source files; zero means unlimited.
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
     * Returns the finite budgets used by ordinary CLI tracing and {@link TraceRequest#TraceRequest(String)}.
     * These interactive-example defaults are not measured process-memory ceilings.
     *
     * <table>
     * <caption>Instructor budgets</caption>
     * <tr><th scope="col">Budget</th><th scope="col">Value</th></tr>
     * <tr><td>Trace-phase deadline</td><td>10,000 milliseconds</td></tr>
     * <tr><td>Completed snapshots</td><td>10,000</td></tr>
     * <tr><td>Output per stream</td><td>1,048,576 bytes</td></tr>
     * <tr><td>Reachable objects per snapshot</td><td>10,000</td></tr>
     * <tr><td>Inspected elements per snapshot</td><td>100,000</td></tr>
     * <tr><td>Accounted retained snapshot storage</td><td>67,108,864 bytes</td></tr>
     * <tr><td>Submitted source</td><td>1,048,576 UTF-8 bytes across at most 128 files</td></tr>
     * <tr><td>Multithread capture</td><td>64 live application threads and 4,096 total frames per snapshot</td></tr>
     * <tr><td>Accounted storage per snapshot</td><td>8,388,608 bytes</td></tr>
     * </table>
     *
     * @return Finite budgets for interactive examples.
     */
    public static TraceLimits instructorDefaults() {
        return new TraceLimits(10_000, 10_000, 1_048_576, 10_000, 100_000, 67_108_864, 1_048_576, 128, 64, 4096,
                8_388_608);
    } // instructorDefaults

    /**
     * Returns a value with every budget set to zero, without selecting an inspection policy.
     * Fixed nesting and representation checks still apply during tracing and rendering.
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
     * Setters retain values without range validation; {@link #build()} validates all budgets together.
     * Unchanged settings retain their initial values, and later changes do not affect previously
     * built values. Zero continues to mean unlimited for every budget.
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
         * Sets the elapsed deadline beginning at the trace phase.
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
         * Sets the completed-capture budget, independently of how many snapshots remain retained.
         *
         * @param snapshots Maximum completed snapshots captured, including replaced snapshots.
         * @return This builder.
         */
        public Builder snapshots(long snapshots) {
            this.snapshots = snapshots;
            return this;
        } // snapshots

        /**
         * Sets the retained-output budget applied independently to standard output and standard error.
         *
         * @param outputBytes Maximum retained bytes per guest output stream.
         * @return This builder.
         */
        public Builder outputBytes(long outputBytes) {
            this.outputBytes = outputBytes;
            return this;
        } // outputBytes

        /**
         * Sets the reachable-object budget for each snapshot.
         *
         * @param heapObjects Maximum distinct reachable objects per snapshot.
         * @return This builder.
         */
        public Builder heapObjects(long heapObjects) {
            this.heapObjects = heapObjects;
            return this;
        } // heapObjects

        /**
         * Sets the inspected-element budget for each snapshot.
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
         * Sets the accounted storage budget for retained snapshots and the snapshot being built.
         * The previous retained state remains charged while a replacement is built and admitted.
         *
         * @param traceBytes Maximum accounted retained snapshot bytes.
         * @return This builder.
         */
        public Builder traceBytes(long traceBytes) {
            this.traceBytes = traceBytes;
            return this;
        } // traceBytes

        /**
         * Sets the submitted-source budget, excluding discovered sources and binary dependencies.
         *
         * @param sourceBytes Maximum UTF-8 source bytes.
         * @return This builder.
         */
        public Builder sourceBytes(long sourceBytes) {
            this.sourceBytes = sourceBytes;
            return this;
        } // sourceBytes

        /**
         * Sets the submitted source-file budget.
         *
         * @param sourceFiles Maximum delimiter-defined source files; undelimited input counts as one.
         * @return This builder.
         */
        public Builder sourceFiles(long sourceFiles) {
            this.sourceFiles = sourceFiles;
            return this;
        } // sourceFiles

        /**
         * Sets the live application-thread budget used only during multithread capture.
         *
         * @param threads Maximum live application threads in multithread capture.
         * @return This builder.
         */
        public Builder threads(long threads) {
            this.threads = threads;
            return this;
        } // threads

        /**
         * Sets the total frame budget used only during multithread capture.
         *
         * @param frames Maximum total frames in multithread capture before filtering application stacks.
         * @return This builder.
         */
        public Builder frames(long frames) {
            this.frames = frames;
            return this;
        } // frames

        /**
         * Sets the accounted storage budget for one snapshot in either capture mode.
         *
         * @param snapshotBytes Maximum accounted bytes for one snapshot.
         * @return This builder.
         */
        public Builder snapshotBytes(long snapshotBytes) {
            this.snapshotBytes = snapshotBytes;
            return this;
        } // snapshotBytes

        /**
         * Builds an independent immutable value, validating every budget without starting tracing.
         *
         * @return A new set of trace budgets.
         * @throws IllegalArgumentException If a budget is negative or the timeout exceeds
         *     {@code Long.MAX_VALUE / 1_000_000} milliseconds.
         */
        public TraceLimits build() {
            return new TraceLimits(this.timeoutMillis, this.snapshots, this.outputBytes, this.heapObjects,
                    this.elements, this.traceBytes, this.sourceBytes, this.sourceFiles, this.threads,
                    this.frames, this.snapshotBytes);
        } // build

    } // Builder

} // TraceLimits
