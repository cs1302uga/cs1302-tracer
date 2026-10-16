package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import java.nio.charset.StandardCharsets;

/**
 * Owns the output range of one guest job. The execution path drains the streams before finishing the job;
 * finished output and detached snapshot prefixes remain valid when the drainers are reused.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class JobOutput {

    private static final byte[] TOOL_OPTIONS = "Picked up JAVA_TOOL_OPTIONS:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAVA_OPTIONS = "Picked up _JAVA_OPTIONS:".getBytes(StandardCharsets.UTF_8);

    private final StreamDrainer out;
    private final StreamDrainer err;
    private final int startOut;
    private final int startErr;
    private Finished finished;

    /**
     * Creates an output range with explicit offsets for an existing capture.
     *
     * @param out Standard output drainer.
     * @param err Standard error drainer.
     * @param startOut First byte belonging to the job on stdout.
     * @param startErr First byte belonging to the job on stderr.
     */
    JobOutput(StreamDrainer out, StreamDrainer err, int startOut, int startErr) {
        this.out = out;
        this.err = err;
        this.startOut = startOut;
        this.startErr = startErr;
    } // JobOutput

    /**
     * Begins a job after any bytes already retained in reusable drainers.
     *
     * @param out Standard output drainer.
     * @param err Standard error drainer.
     * @return Output owner for the new job.
     */
    public static JobOutput begin(StreamDrainer out, StreamDrainer err) {
        return new JobOutput(out, err, out.size(), err.size());
    } // begin

    /**
     * Owns all output of a dedicated guest, including bytes drained before capture started.
     *
     * @param out Standard output drainer.
     * @param err Standard error drainer.
     * @return Output owner starting at the beginning of both streams.
     */
    public static JobOutput fromStart(StreamDrainer out, StreamDrainer err) {
        return new JobOutput(out, err, 0, 0);
    } // fromStart

    /**
     * Drains to guest-reported job byte counts, using quiet-period draining when counts are unavailable.
     * The caller must first establish that the guest has completed or stopped producing output.
     *
     * @param expectedOut Expected stdout byte count, or zero if unavailable.
     * @param expectedErr Expected stderr byte count, or zero if unavailable.
     */
    public void drain(long expectedOut, long expectedErr) {
        if (this.finished != null) {
            return;
        } // if
        drain(this.out, this.startOut, expectedOut);
        drain(this.err, this.startErr, expectedErr);
    } // drain

    /**
     * Drains one stream to a job-relative target.
     *
     * @param drainer Stream to drain.
     * @param start Starting offset.
     * @param expected Expected job byte count.
     */
    private static void drain(StreamDrainer drainer, int start, long expected) {
        if (expected > 0) {
            drainer.syncUntil(start + expected, 1000);
        } else {
            drainer.sync();
        } // if
    } // drain

    /**
     * Returns the current stdout prefix, or immutable final stdout after finishing.
     *
     * @return Standard output slice.
     */
    public OutputSlice stdout() {
        return this.finished != null ? this.finished.stdout()
                : OutputSlice.from(this.out, this.startOut, Math.max(0, this.out.size() - this.startOut));
    } // stdout

    /**
     * Returns the sanitized stderr prefix, or immutable final stderr after finishing.
     *
     * @return Standard error slice.
     */
    public OutputSlice stderr() {
        return this.finished != null ? this.finished.stderr() : sanitizeStderr(
                OutputSlice.from(this.err, this.startErr, Math.max(0, this.err.size() - this.startErr)));
    } // stderr

    /**
     * Captures fixed output lengths and their raw byte cost together for snapshot accounting.
     *
     * @return Live output prefixes, to be detached before drainer reuse.
     */
    Prefix capture() {
        int outLength = Math.max(0, this.out.size() - this.startOut);
        int errLength = Math.max(0, this.err.size() - this.startErr);
        return new Prefix(OutputSlice.from(this.out, this.startOut, outLength),
                sanitizeStderr(OutputSlice.from(this.err, this.startErr, errLength)), (long) outLength + errLength);
    } // capture

    /**
     * Output prefixes with matching allocation accounting.
     *
     * @param stdout Standard output prefix.
     * @param stderr Sanitized standard error prefix.
     * @param rawBytes Raw byte count including diagnostic banners.
     */
    record Prefix(OutputSlice stdout, OutputSlice stderr, long rawBytes) {} // Prefix

    /**
     * Freezes output after draining. Repeated calls return the same result, even after drainer reset.
     * Live snapshot prefixes must be detached using the result before resetting the drainers.
     *
     * @return Immutable final output.
     */
    public synchronized Finished finish() {
        if (this.finished == null) {
            this.finished = new Finished(stdout(), stderr());
        } // if
        return this.finished;
    } // finish

    /**
     * Removes only leading JVM options banners, preserving guest stderr bytes.
     *
     * @param raw Raw stderr, or null for empty output.
     * @return Sanitized slice.
     */
    public static OutputSlice sanitizeStderr(OutputSlice raw) {
        OutputSlice current = raw == null ? OutputSlice.empty() : raw;
        while (current.startsWith(TOOL_OPTIONS) || current.startsWith(JAVA_OPTIONS)) {
            int newline = current.indexOf((byte) '\n', 0);
            if (newline == -1) {
                return OutputSlice.empty();
            } // if
            current = current.subSlice(newline + 1);
        } // while
        return current;
    } // sanitizeStderr

    /**
     * Immutable output shared by all detached snapshot prefixes of a completed job.
     *
     * @param stdout Final stdout.
     * @param stderr Final sanitized stderr.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record Finished(OutputSlice stdout, OutputSlice stderr) {
        /**
         * Freezes supplied slices so this result never refers to reusable drainers.
         *
         * @param stdout Final stdout slice.
         * @param stderr Final stderr slice.
         */
        public Finished {
            stdout = stdout.materialize();
            stderr = stderr.materialize();
        } // Finished

        /**
         * Detaches a snapshot while retaining its original output prefix lengths and other metadata.
         *
         * @param snapshot Snapshot captured during this job.
         * @return Snapshot backed by immutable final output.
         */
        public ExecutionSnapshot detach(ExecutionSnapshot snapshot) {
            return snapshot.withOutput(this.stdout.subSlice(0, snapshot.stdoutLength()),
                    this.stderr.subSlice(0, snapshot.stderrLength()));
        } // detach
    } // Finished
} // JobOutput
