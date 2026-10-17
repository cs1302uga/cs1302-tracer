package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.batch.BatchTraceService;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

/**
 * Owns a bounded worker pool for the existing NDJSON batch protocol.
 * Use try-with-resources and invoke {@link #processStream(InputStream, Writer)} sequentially.
 * A processor may handle consecutive streams until closed; caller-owned streams remain open.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class BatchTracer implements AutoCloseable {

    private final BatchTraceService service;

    /**
     * Creates a batch processor; close it to release worker processes and scheduler resources.
     * All numeric settings must be positive.
     *
     * @param workers Concurrent worker sessions.
     * @param maxJobsPerWorker Jobs before recycling a guest.
     * @param maxInFlight Maximum admitted requests, including responses buffered for ordered output.
     * @param completionOrder Whether to emit completion order instead of submission order.
     * @throws IllegalArgumentException If any numeric setting is less than one.
     */
    public BatchTracer(int workers, int maxJobsPerWorker, int maxInFlight, boolean completionOrder) {
        this.service = new BatchTraceService(workers, maxJobsPerWorker, maxInFlight, completionOrder);
    } // BatchTracer

    /**
     * Starts configuration of an independently owned batch processor.
     * The three numeric settings are required; submission-order output is the default.
     *
     * @return A new builder, without allocated workers or scheduler resources.
     * @since 4.1.0
     */
    public static Builder builder() {
        return new Builder();
    } // builder

    /**
     * Mutable, reusable configuration; not thread-safe. Each successful build creates
     * a fresh processor that its caller must close. Configuration allocates no workers
     * or scheduler resources and never copies a running processor's state.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        private Integer workers;
        private Integer maxJobsPerWorker;
        private Integer maxInFlight;
        private boolean completionOrder;

        /** Starts with required numeric settings unset. */
        private Builder() {} // Builder

        /**
         * Sets the number of concurrent worker sessions.
         * @param value Required positive worker count, validated when built.
         * @return This builder.
         */
        public Builder workers(int value) {
            this.workers = value;
            return this;
        } // workers

        /**
         * Sets the guest recycling interval.
         * @param value Required positive jobs per worker, validated when built.
         * @return This builder.
         */
        public Builder maxJobsPerWorker(int value) {
            this.maxJobsPerWorker = value;
            return this;
        } // maxJobsPerWorker

        /**
         * Sets the maximum admitted requests, including buffered responses.
         * @param value Required positive admission limit, validated when built.
         * @return This builder.
         */
        public Builder maxInFlight(int value) {
            this.maxInFlight = value;
            return this;
        } // maxInFlight

        /**
         * Selects completion-order output instead of the default submission order.
         * @param value Whether to emit responses as jobs finish.
         * @return This builder.
         */
        public Builder completionOrder(boolean value) {
            this.completionOrder = value;
            return this;
        } // completionOrder

        /**
         * Creates an independently owned processor after validating its settings.
         * @return A fresh processor; close it to release its resources.
         * @throws IllegalStateException If any required numeric setting is unset.
         * @throws IllegalArgumentException If any numeric setting is less than one.
         */
        public BatchTracer build() {
            if (this.workers == null || this.maxJobsPerWorker == null || this.maxInFlight == null) {
                throw new IllegalStateException("workers, maxJobsPerWorker, and maxInFlight are required");
            } // if
            return new BatchTracer(this.workers, this.maxJobsPerWorker, this.maxInFlight, this.completionOrder);
        } // build

    } // Builder

    /**
     * Processes UTF-8 NDJSON jobs and flushes their responses without closing caller-owned streams.
     * Blank input lines are ignored. The call normally returns after input reaches EOF and every
     * admitted response has been written. Invoke sequentially; a processor must not be used after closing.
     *
     * @param input Request stream.
     * @param output Response destination.
     * @throws IOException On request or response I/O failure.
     */
    public void processStream(InputStream input, Writer output) throws IOException {
        this.service.processStream(input, output);
    } // processStream

    /**
     * Shuts down the scheduler and closes all owned workers and guest processes.
     * Call after stream processing finishes. This does not close any caller-supplied stream or writer.
     */
    @Override
    public void close() {
        this.service.close();
    } // close

} // BatchTracer
