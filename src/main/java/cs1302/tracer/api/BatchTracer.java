package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.internal.batch.BatchTraceService;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

/** Owns a bounded worker pool for the existing NDJSON batch protocol. */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class BatchTracer implements AutoCloseable {
    private final BatchTraceService service;

    /**
     * Creates a batch processor; close it to release worker processes and scheduler resources.
     * Numeric settings below one are clamped to one, matching the command-line batch behavior.
     *
     * @param workers Concurrent worker sessions.
     * @param maxJobsPerWorker Jobs before recycling a guest.
     * @param maxInFlight Maximum admitted requests.
     * @param completionOrder Whether to emit completion order instead of submission order.
     */
    public BatchTracer(int workers, int maxJobsPerWorker, int maxInFlight, boolean completionOrder) {
        this.service = new BatchTraceService(workers, maxJobsPerWorker, maxInFlight, completionOrder);
    } // BatchTracer

    /**
     * Processes UTF-8 NDJSON jobs and flushes their responses without closing caller-owned streams.
     * Invoke sequentially; a processor must not be used after closing.
     *
     * @param input Request stream.
     * @param output Response destination.
     * @throws IOException On request or response I/O failure.
     */
    public void processStream(InputStream input, Writer output) throws IOException {
        this.service.processStream(input, output);
    } // processStream

    @Override
    public void close() {
        this.service.close();
    } // close
} // BatchTracer
