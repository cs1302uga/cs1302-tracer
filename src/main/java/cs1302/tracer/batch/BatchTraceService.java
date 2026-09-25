package cs1302.tracer.batch;

import com.google.gson.Gson;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.NestingException;
import cs1302.tracer.model.TraceFormat;
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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Service managing pool of batch trace workers and streaming NDJSON requests/responses.
 */
public final class BatchTraceService implements AutoCloseable {

    /** Maximum allowed characters in a single NDJSON input record (16 MB). */
    public static final int MAX_RECORD_CHARS = 16 * 1024 * 1024;

    private final int workerCount;
    private final int maxInFlight;
    private final boolean completionOrder;
    private final int maxJobsPerWorker;
    private final ExecutorService executor;
    private final BlockingQueue<BatchTraceWorker> workerPool;
    private final List<BatchTraceWorker> allWorkers;
    private final Gson gson;
    private final Function<BatchJobRequest, BatchJobResponse> runner;

    /**
     * Constructs a batch trace service with specified concurrency and recycling limits.
     *
     * @param workerCount Number of concurrent worker sessions.
     * @param maxJobsPerWorker Maximum jobs before recycling a worker.
     */
    public BatchTraceService(int workerCount, int maxJobsPerWorker) {
        this(workerCount, maxJobsPerWorker, Math.max(16, workerCount * 2), false);
    } // BatchTraceService

    /**
     * Constructs a bounded batch scheduler with explicit output ordering.
     * @param workerCount Concurrent guests.
     * @param maxJobsPerWorker Jobs before recycling a reusable guest.
     * @param maxInFlight Maximum admitted jobs, including buffered results.
     * @param completionOrder Emit responses immediately as jobs finish.
     */
    public BatchTraceService(int workerCount, int maxJobsPerWorker,
            int maxInFlight, boolean completionOrder) {
        this(workerCount, maxJobsPerWorker, maxInFlight, completionOrder, null);
    } // BatchTraceService

    /**
     * Constructs a scheduler with a replaceable job boundary for deterministic scheduling tests.
     * @param workerCount Concurrent guests.
     * @param maxJobsPerWorker Recycling limit.
     * @param maxInFlight Admission limit.
     * @param completionOrder Emit completion order.
     * @param runner Job boundary, or null to use the guest worker pool.
     */
    BatchTraceService(int workerCount, int maxJobsPerWorker, int maxInFlight,
            boolean completionOrder, Function<BatchJobRequest, BatchJobResponse> runner) {
        if (workerCount < 1 || maxInFlight < 1 || maxJobsPerWorker < 1) {
            throw new IllegalArgumentException(
                    "Worker, in-flight, and recycling limits must be positive");
        } // if
        this.workerCount = workerCount;
        this.maxInFlight = maxInFlight;
        this.completionOrder = completionOrder;
        this.maxJobsPerWorker = maxJobsPerWorker;
        this.executor = Executors.newFixedThreadPool(this.workerCount);
        this.workerPool = new LinkedBlockingQueue<>();
        this.allWorkers = new ArrayList<>();
        this.gson = PyTutorSerializer.getGson(false);
        this.runner = runner == null ? this::executeJob : runner;

        for (int i = 0; i < this.workerCount; i++) {
            BatchTraceWorker worker = new BatchTraceWorker(this.maxJobsPerWorker);
            allWorkers.add(worker);
            workerPool.offer(worker);
        } // for
    } // BatchTraceService

    /**
     * Reads a line bounded by maximum allowed characters.
     *
     * @param reader Reader to read characters from.
     * @param maxChars Maximum characters before aborting line.
     * @return Next line or null at EOF.
     * @throws IOException If record exceeds maxChars or on I/O error.
     */
    static String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                break;
            } else if (c == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next != '\n') {
                    reader.reset();
                } // if
                break;
            } // if
            sb.append((char) c);
            if (sb.length() > maxChars) {
                while ((c = reader.read()) != -1) {
                    if (c == '\n') {
                        break;
                    } else if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n') {
                            reader.reset();
                        } // if
                        break;
                    } // if
                } // while
                throw new IOException("NDJSON record exceeds maximum size of "
                        + maxChars + " characters");
            } // if
        } // while
        return sb.length() == 0 && c == -1 ? null : sb.toString();
    } // readBoundedLine

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
        Queue<CompletableFuture<Void>> inFlight = new ArrayDeque<>();
        CompletableFuture<Void> previous = CompletableFuture.completedFuture(null);

        String line;
        while ((line = readBoundedLine(reader, MAX_RECORD_CHARS)) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            } // if
            while (inFlight.size() >= maxInFlight) {
                if (completionOrder) {
                    CompletableFuture.anyOf(inFlight.toArray(CompletableFuture[]::new)).join();
                    inFlight.removeIf(CompletableFuture::isDone);
                } else {
                    inFlight.poll().join();
                } // if
                checkWriter(printWriter);
            } // while
            CompletableFuture<BatchJobResponse> job = submitJob(trimmed);
            CompletableFuture<Void> future = completionOrder
                    ? job.thenAccept(response -> writeResponse(response, printWriter))
                    : previous.thenCombine(job, (ignored, response) -> {
                        writeResponse(response, printWriter);
                        return null;
                    });
            previous = future;
            inFlight.add(future);
        } // while

        while (!inFlight.isEmpty()) {
            inFlight.poll().join();
            checkWriter(printWriter);
        } // while
        printWriter.flush();
    } // processStream

    /**
     * Submits a single raw JSON line for execution and writes its response when done.
     *
     * @param jsonLine Raw JSON request line.
     * @return Future completing when the response is ready.
     */
    private CompletableFuture<BatchJobResponse> submitJob(String jsonLine) {
        return CompletableFuture.supplyAsync(() -> {
            BatchJobResponse response;
            BatchJobRequest req = null;
            try {
                JsonNesting.validate(jsonLine);
                req = gson.fromJson(jsonLine, BatchJobRequest.class);
            } catch (NestingException nesting) {
                return new BatchJobResponse(null, new TraceResult(1, "unknown", "stopped",
                        nesting.getMessage(), "parse", false, null,
                        TraceLimits.unlimited(), Map.of(),
                        List.of(nesting.getMessage()), "", ""));
            } catch (Exception parseErr) {
                TraceResult errResult = TraceResult.failed(
                        "unknown", "parse", "Malformed JSON request: " + parseErr.getMessage());
                response = new BatchJobResponse(null, errResult);
                return response;
            } // try

            if (req == null) {
                TraceResult errResult = TraceResult.failed(
                        "unknown", "parse", "Empty JSON request");
                response = new BatchJobResponse(null, errResult);
                return response;
            } // if

            return runner.apply(req);
        }, executor);
    } // submitJob

    /**
     * Checks writer failures after an output future completes.
     * @param writer Output writer.
     * @throws IOException If writing failed.
     */
    private void checkWriter(PrintWriter writer) throws IOException {
        if (writer.checkError()) {
            throw new IOException("Failed to write batch response");
        } // if
    } // checkWriter

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
            TraceFormat format = TraceFormat.PYTUTOR;
            try {
                format = req.resolveFormat();
            } catch (Exception ignored) {
                // fallback to default
            } // try
            TraceResult errResult = TraceResult.stopped(
                    format.name().toLowerCase(java.util.Locale.ROOT),
                    "interrupted", "Worker execution interrupted", req.resolveLimits());
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
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } // if
        } catch (InterruptedException e) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Ignore during shutdown
            } finally {
                Thread.currentThread().interrupt();
            } // try
        } finally {
            for (BatchTraceWorker worker : allWorkers) {
                worker.close();
            } // for
        } // try
    } // close
}
