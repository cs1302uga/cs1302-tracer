package cs1302.tracer.api;

import org.apiguardian.api.API;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Detached job outcome with already serialized JSON, valid after guest cleanup and reuse.
 * Outcomes returned by {@link Tracer#trace(TraceRequest)} contain coherent metadata and JSON.
 * Direct construction and the builder also accept arbitrary, possibly null strings; they do not
 * validate status combinations, parse JSON, or synchronize JSON with other components.
 *
 * <p>For tracer-produced outcomes, {@code completed} and {@code complete=true} mean that no stop or failure
 * was recorded. {@code failed} identifies an observed execution, preparation, or tracer failure;
 * {@code stopped} identifies a controlled stop such as a resource or representation limit.
 * These fields do not prove that the guest program behaved correctly. In particular, the single-stack
 * exception-recognition limitation described by {@link Tracer} can leave an uncaught JDK or dependency
 * exception only in {@code stderr} while completion metadata indicates success.</p>
 *
 * <p>Use {@code stopReason} for machine-readable classification and {@code diagnostics} for explanatory
 * messages; diagnostic text is not a stable identifier. Captured guest stderr is separate from diagnostics
 * and can contain ordinary program output as well as stack traces. A missing payload is the text
 * {@code "null"} in {@code traceJson}, distinct from an available trace containing an empty step sequence.</p>
 *
 * <p>Available counters depend on how far execution progressed. {@code snapshotsCaptured} counts committed
 * snapshots, including those later replaced; {@code snapshotsRetained} is the remaining history size.
 * {@code droppedSnapshots} is a zero-or-one indicator that an unfinished snapshot was discarded, not the
 * number of replaced snapshots. {@code retainedBytes} is tracer accounting, not process memory or response size.
 * {@code elapsedMillis} measures time from entry into the trace phase, excluding source preparation and
 * reusable-guest startup. Full JSON uses the
 * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/BOUNDED_TRACING.md#envelope">
 * version 1 result envelope</a>; {@code traceJson} contains only its format-specific sequence payload as defined
 * by the <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md">output reference</a>.
 * JSON strings and metadata remain valid after the producing worker is closed or reused.</p>
 *
 * @param format Selected output format; tracing produces {@code pytutor} or {@code modern}.
 * @param status Job status; tracing produces {@code completed}, {@code failed}, or {@code stopped}.
 * @param stopReason Machine-readable stop reason, or null when tracing recorded no stop or failure.
 * @param phase Last execution phase; successful API jobs report {@code serialize}. May be null in supplied outcomes.
 * @param complete Whether tracing recorded completion without a stop or failure; subject to the class limitations.
 * @param counters Resource accounting; copied without preserving a particular iteration order.
 * @param diagnostics Job diagnostics; copied in their supplied order.
 * @param stdout Captured guest output, possibly null for a manually constructed outcome.
 * @param stderr Captured guest error output, possibly null for a manually constructed outcome.
 * @param traceJson Trace payload text; tracing supplies the JSON literal {@code null} when no payload is available.
 * @param json Result-envelope text, possibly null for a manually constructed outcome.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceOutcome(String format, String status, String stopReason, String phase, boolean complete,
        Map<String, Long> counters, List<String> diagnostics, String stdout, String stderr,
        String traceJson, String json) {

    /**
     * Takes shallow immutable copies of counters and diagnostics, leaving strings unchanged.
     *
     * @throws NullPointerException If a collection, counter key or value, or diagnostic entry is null.
     */
    public TraceOutcome {
        counters = Map.copyOf(counters);
        diagnostics = List.copyOf(diagnostics);
    } // TraceOutcome

    /**
     * Creates a builder with empty counters and diagnostics, false completion, and null strings.
     * The builder accepts explicitly supplied values; it does not generate or validate JSON.
     *
     * @return A new builder.
     * @since 4.1.0
     */
    public static Builder builder() {
        return new Builder();
    } // builder

    /**
     * Copies every value into a builder, preserving both JSON strings verbatim.
     * Changing metadata in the builder does not update either serialized string. The caller
     * must supply corresponding JSON when consistency between metadata and JSON is required.
     *
     * @return A builder initialized with this outcome.
     * @since 4.1.0
     */
    public Builder toBuilder() {
        return builder()
                .format(this.format)
                .status(this.status)
                .stopReason(this.stopReason)
                .phase(this.phase)
                .complete(this.complete)
                .counters(this.counters)
                .diagnostics(this.diagnostics)
                .stdout(this.stdout)
                .stderr(this.stderr)
                .traceJson(this.traceJson)
                .json(this.json);
    } // toBuilder

    /**
     * Writes the supplied result-envelope string verbatim without flushing or closing the caller's writer.
     * A manually constructed outcome must have a non-null {@code json} value before this method is used.
     *
     * @param destination Caller-owned destination.
     * @throws IOException If the destination cannot accept the JSON.
     * @throws NullPointerException If the destination is null; standard writers also reject a null JSON string.
     */
    public void writeJson(Writer destination) throws IOException {
        destination.write(this.json);
    } // writeJson

    /**
     * Mutable, reusable construction of explicitly supplied outcome values; not thread-safe.
     * Collection setters take shallow immutable copies and reject null collections or entries.
     * Strings may be null, matching the canonical constructor. Changing this builder does not
     * change a previously built outcome.
     *
     * <p>This builder does not generate JSON, validate JSON, or synchronize metadata with the
     * supplied JSON strings. Production trace rendering supplies those values from one execution
     * result. Callers constructing or adapting outcomes are responsible for their consistency.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        /** Selected output format. */
        private String format;
        /** Completion, failure, or stopped status. */
        private String status;
        /** Machine-readable stop reason. */
        private String stopReason;
        /** Last execution phase. */
        private String phase;
        /** Whether the job completed. */
        private boolean complete;
        /** Detached resource counters. */
        private Map<String, Long> counters = Map.of();
        /** Detached diagnostics. */
        private List<String> diagnostics = List.of();
        /** Captured guest output. */
        private String stdout;
        /** Captured guest error output. */
        private String stderr;
        /** Explicitly supplied trace payload JSON. */
        private String traceJson;
        /** Explicitly supplied result-envelope JSON. */
        private String json;

        /** Creates an empty outcome builder. */
        private Builder() {} // Builder

        /**
         * Sets the output format without parsing or validation.
         * @param value Selected output format, possibly null.
         * @return This builder.
         */
        public Builder format(String value) {
            this.format = value;
            return this;
        } // format

        /**
         * Sets the status without deriving other outcome fields.
         * @param value Outcome status, possibly null.
         * @return This builder.
         */
        public Builder status(String value) {
            this.status = value;
            return this;
        } // status

        /**
         * Sets the stop reason.
         * @param value Machine-readable stop reason, possibly null.
         * @return This builder.
         */
        public Builder stopReason(String value) {
            this.stopReason = value;
            return this;
        } // stopReason

        /**
         * Sets the last execution phase.
         * @param value Execution phase, possibly null.
         * @return This builder.
         */
        public Builder phase(String value) {
            this.phase = value;
            return this;
        } // phase

        /**
         * Sets completion without deriving status or serialized fields.
         * @param value Whether the job completed.
         * @return This builder.
         */
        public Builder complete(boolean value) {
            this.complete = value;
            return this;
        } // complete

        /**
         * Takes a shallow immutable copy of the resource counters.
         * @param value Resource accounting.
         * @return This builder.
         * @throws NullPointerException If the map, a key, or a value is null.
         */
        public Builder counters(Map<String, Long> value) {
            this.counters = Map.copyOf(value);
            return this;
        } // counters

        /**
         * Takes a shallow immutable copy of the diagnostics in their supplied order.
         * @param value Job diagnostics.
         * @return This builder.
         * @throws NullPointerException If the list or an entry is null.
         */
        public Builder diagnostics(List<String> value) {
            this.diagnostics = List.copyOf(value);
            return this;
        } // diagnostics

        /**
         * Sets captured stdout without updating the supplied envelope JSON.
         * @param value Captured guest output, possibly null.
         * @return This builder.
         */
        public Builder stdout(String value) {
            this.stdout = value;
            return this;
        } // stdout

        /**
         * Sets captured stderr without updating the supplied envelope JSON.
         * @param value Captured guest error output, possibly null.
         * @return This builder.
         */
        public Builder stderr(String value) {
            this.stderr = value;
            return this;
        } // stderr

        /**
         * Sets the trace payload JSON verbatim without validation or envelope synchronization.
         * @param value Serialized trace payload, possibly null.
         * @return This builder.
         */
        public Builder traceJson(String value) {
            this.traceJson = value;
            return this;
        } // traceJson

        /**
         * Sets the result-envelope JSON verbatim without validating it or deriving metadata.
         * @param value Serialized result envelope, possibly null.
         * @return This builder.
         */
        public Builder json(String value) {
            this.json = value;
            return this;
        } // json

        /**
         * Creates a detached outcome using the existing canonical constructor.
         * No JSON parsing, generation, or consistency validation occurs.
         * @return A new outcome with this builder's values.
         */
        public TraceOutcome build() {
            return new TraceOutcome(this.format, this.status, this.stopReason, this.phase, this.complete,
                    this.counters, this.diagnostics, this.stdout, this.stderr, this.traceJson, this.json);
        } // build

    } // Builder

} // TraceOutcome
