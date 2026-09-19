package cs1302.tracer.batch;

import com.google.gson.Gson;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Service managing pool of batch trace workers and streaming NDJSON requests/responses.
 */
public final class BatchTraceService implements AutoCloseable {

    private final int workerCount;
    private final int maxJobsPerWorker;
    private final ExecutorService executor;
    private final BlockingQueue<BatchTraceWorker> workerPool;
    private final List<BatchTraceWorker> allWorkers;
    private final Gson gson;

    /**
     * Constructs a batch trace service with specified concurrency and recycling limits.
     *
     * @param workerCount Number of concurrent worker sessions.
     * @param maxJobsPerWorker Maximum jobs before recycling a worker.
     */
    public BatchTraceService(int workerCount, int maxJobsPerWorker) {
        this.workerCount = Math.max(1, workerCount);
        this.maxJobsPerWorker = maxJobsPerWorker;
        this.executor = Executors.newFixedThreadPool(this.workerCount);
        this.workerPool = new LinkedBlockingQueue<>();
        this.allWorkers = new ArrayList<>();
        this.gson = PyTutorSerializer.getGson(false);

        for (int i = 0; i < this.workerCount; i++) {
            BatchTraceWorker worker = new BatchTraceWorker(this.maxJobsPerWorker);
            allWorkers.add(worker);
            workerPool.offer(worker);
        } // for
    } // BatchTraceService

    /**
     * Processes an NDJSON input stream and writes NDJSON responses to writer.
     *
     * @param input Input stream containing NDJSON lines.
     * @param output Output writer for response NDJSON lines.
     * @throws IOException On stream IO failure.
     */
    public void processStream(InputStream input, Writer output) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        PrintWriter printWriter = new PrintWriter(output, true);
        int maxInFlight = Math.max(16, workerCount * 2);
        Queue<CompletableFuture<Void>> inFlight = new ArrayDeque<>();

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            } // if
            while (inFlight.size() >= maxInFlight) {
                inFlight.poll().join();
            } // while
            CompletableFuture<Void> future = submitJob(trimmed, printWriter);
            inFlight.add(future);
        } // while

        while (!inFlight.isEmpty()) {
            inFlight.poll().join();
        } // while
        printWriter.flush();
    } // processStream

    /**
     * Submits a single raw JSON line for execution and writes its response when done.
     *
     * @param jsonLine Raw JSON request line.
     * @param writer Output writer.
     * @return Future completing when the response is written.
     */
    private CompletableFuture<Void> submitJob(String jsonLine, PrintWriter writer) {
        return CompletableFuture.runAsync(() -> {
            BatchJobResponse response;
            BatchJobRequest req = null;
            try {
                req = gson.fromJson(jsonLine, BatchJobRequest.class);
            } catch (Exception parseErr) {
                TraceResult errResult = TraceResult.failed(
                        "unknown", "parse", "Malformed JSON request: " + parseErr.getMessage());
                response = new BatchJobResponse(null, errResult);
                writeResponse(response, writer);
                return;
            } // try

            if (req == null) {
                TraceResult errResult = TraceResult.failed(
                        "unknown", "parse", "Empty JSON request");
                response = new BatchJobResponse(null, errResult);
                writeResponse(response, writer);
                return;
            } // if

            response = executeJob(req);
            writeResponse(response, writer);
        }, executor);
    } // submitJob

    /**
     * Obtains an available worker from the pool and executes the job request.
     *
     * @param req Job request.
     * @return Batch job response.
     */
    BatchJobResponse executeJob(BatchJobRequest req) {
        BatchTraceWorker worker = null;
        try {
            worker = workerPool.take();
            return worker.execute(req);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            TraceResult errResult = TraceResult.stopped(
                    req.resolveFormat().name().toLowerCase(java.util.Locale.ROOT),
                    "interrupted", "Worker execution interrupted");
            return new BatchJobResponse(req.id(), errResult);
        } finally {
            if (worker != null) {
                workerPool.offer(worker);
            } // if
        } // try
    } // executeJob

    /**
     * Writes a batch response to the synchronized output writer.
     *
     * @param response Batch job response.
     * @param writer Output writer.
     */
    private synchronized void writeResponse(BatchJobResponse response, PrintWriter writer) {
        String json = gson.toJson(response);
        writer.println(json);
        writer.flush();
    } // writeResponse

    @Override
    public void close() {
        executor.shutdown();
        for (BatchTraceWorker worker : allWorkers) {
            worker.close();
        } // for
    } // close
}
