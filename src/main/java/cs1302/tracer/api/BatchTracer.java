package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.batch.BatchTraceService;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

/**
 * Owns a bounded worker pool for the existing NDJSON batch protocol.
 * Use try-with-resources. A processor runs one {@link #processStream(InputStream, Writer)} call at a time;
 * do not overlap calls on the same processor, even from different threads. Separate processors and
 * {@link Tracer} calls may run concurrently in one JVM.
 * A processor may handle consecutive streams until closed; caller-owned streams remain open.
 *
 * <p>Worker and admission counts bound scheduling, not each job's execution or resource use.
 * NDJSON requests with omitted {@code limits} have unlimited job budgets, unlike the finite defaults of
 * {@link TraceRequest#TraceRequest(String)}. Supply explicit per-job limits when bounded tracing is required.
 * Request fields, defaults, response envelopes, and ordering are defined by the
 * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/batch.md">batch protocol</a>.</p>
 *
 * <p>Single-stack jobs reuse a guest JVM until its recycling interval or a guest failure requires replacement.
 * Their entry-point return ends the job; lingering job threads are interrupted. Selected process state is reset,
 * but other JVM-global state and filesystem effects can persist across jobs. Set {@code maxJobsPerWorker} to
 * {@code 1} to avoid reuse between executed guest jobs; this is not a sandbox. Multithread jobs use fresh
 * dedicated guests.
 * Unexpected reusable-guest termination, including {@code System.exit(0)}, is reported as {@code guest_exit}
 * unless an earlier stop or failure takes precedence.
 * Host JDK, subprocess, and filesystem packaging requirements are the same as for {@link Tracer}.</p>
 *
 * <p>Successful stream processing does not establish successful guest execution. Inspect every response's
 * {@code status}, {@code stopReason}, {@code complete}, and captured output. The single-stack
 * exception-recognition limitation described by {@link Tracer} also applies to reusable batch guests.</p>
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class BatchTracer implements AutoCloseable {

    private final BatchTraceService service;

    /**
     * Creates a batch processor; close it to release worker processes and scheduler resources.
     * All numeric settings must be positive.
     *
     * @param workers Concurrent worker sessions.
     * @param maxJobsPerWorker Maximum completed jobs per reusable guest; one prevents reuse between executed jobs.
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
         * Sets the reusable guest's completed-job recycling interval.
         * @param value Required positive completed jobs per guest, validated when built.
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
     * admitted response has been written. Invoke sequentially: calls on one processor must not overlap.
     * A processor must not be used after closing.
     * Malformed requests and job failures are normally encoded as individual responses; inspect those responses
     * even when this method returns normally. Request and response I/O failures are reported to the caller.
     * An I/O failure does not cancel or wait for all admitted jobs: they can write responses after this method
     * throws. Close the processor after such a failure and keep its writer dedicated to that processor;
     * do not immediately reuse the writer for another stream or assume all response writing has stopped.
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
     * Shutdown normally allows 30 seconds for scheduler termination, then requests interruption and waits
     * another 30 seconds if necessary before closing workers. Worker cleanup can add time; these waits are
     * not a whole-job deadline. Already admitted jobs can continue producing responses during shutdown.
     */
    @Override
    public void close() {
        this.service.close();
    } // close

} // BatchTracer
