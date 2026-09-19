package cs1302.tracer.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import cs1302.tracer.App;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration and lifecycle tests for BatchTraceService and CLI batch-trace command.
 */
class BatchTraceServiceTest {

    private static final String SIMPLE_SOURCE = """
            public class StreamProg {
                public static void main(String[] args) {
                    int x = 10;
                    System.out.println(x);
                }
            }
            """;

    @Test
    @DisplayName("Service processes stream of NDJSON jobs")
    void testProcessStream() throws Exception {
        Gson gson = PyTutorSerializer.getGson(false);
        BatchJobRequest req1 = new BatchJobRequest(
                "s-1", SIMPLE_SOURCE, "pytutor", null, List.of("4"),
                false, false, false, false, false, "fqn", null, null);
        BatchJobRequest req2 = new BatchJobRequest(
                "s-2", SIMPLE_SOURCE, "modern", null, List.of("4"),
                false, false, false, false, false, "fqn", null, null);

        String ndjson = gson.toJson(req1) + "\n\n" + gson.toJson(req2) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        StringWriter out = new StringWriter();

        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(in, out);
        } // try

        String[] lines = out.toString().trim().split("\\R");
        assertThat(lines).hasSize(2);

        BatchJobResponse resp1 = gson.fromJson(lines[0], BatchJobResponse.class);
        BatchJobResponse resp2 = gson.fromJson(lines[1], BatchJobResponse.class);

        assertThat(resp1.id()).isEqualTo("s-1");
        assertThat(resp1.result().complete()).isTrue();
        assertThat(resp2.id()).isEqualTo("s-2");
        assertThat(resp2.result().complete()).isTrue();
    } // testProcessStream

    @Test
    @DisplayName("Service handles malformed and empty JSON lines gracefully")
    void testMalformedJson() throws Exception {
        Gson gson = PyTutorSerializer.getGson(false);
        String ndjson = "{not valid json\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        StringWriter out = new StringWriter();

        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(in, out);
        } // try

        String[] lines = out.toString().trim().split("\\R");
        assertThat(lines).hasSize(1);
        BatchJobResponse resp = gson.fromJson(lines[0], BatchJobResponse.class);
        assertThat(resp.id()).isNull();
        assertThat(resp.result().status()).isEqualTo("failed");
    } // testMalformedJson

    @Test
    @DisplayName("App batch-trace subcommand executes via CLI options and input file")
    void testAppBatchTraceCommand(@TempDir File tempDir) throws Exception {
        Gson gson = PyTutorSerializer.getGson(false);
        BatchJobRequest req = new BatchJobRequest(
                "cli-1", SIMPLE_SOURCE, "pytutor", null, List.of("4"),
                false, false, false, false, false, "fqn", null, null);
        File inputFile = new File(tempDir, "jobs.ndjson");
        Files.writeString(inputFile.toPath(), gson.toJson(req) + "\n");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        try {
            int exitCode = App.execute(new String[]{
                "batch-trace", "--input", inputFile.getAbsolutePath(), "--workers", "1"
            });
            assertThat(exitCode).isEqualTo(0);
        } finally {
            System.setOut(originalOut);
        } // try

        String output = capturedOut.toString(StandardCharsets.UTF_8).trim();
        assertThat(output).isNotEmpty();
        BatchJobResponse resp = gson.fromJson(output, BatchJobResponse.class);
        assertThat(resp.id()).isEqualTo("cli-1");
        assertThat(resp.result().complete()).isTrue();
    } // testAppBatchTraceCommand

    @Test
    @DisplayName("App batch-trace subcommand displays help")
    void testAppBatchTraceHelp() {
        int exitCode = App.execute(new String[]{"batch-trace", "--help"});
        assertThat(exitCode).isEqualTo(0);
    } // testAppBatchTraceHelp

    @Test
    @DisplayName("App batch-trace handles missing input file")
    void testAppBatchTraceMissingFile() {
        AtomicInteger exit = new AtomicInteger(-1);
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            App.BatchTrace batchTrace = new App.BatchTrace();
            batchTrace.exitHandler = code -> exit.set(code);
            new picocli.CommandLine(batchTrace).execute(
                    "--input", "/non/existent/path/never_created.ndjson");
            assertThat(exit.get()).isEqualTo(1);
        } finally {
            System.setErr(origErr);
        } // try
    } // testAppBatchTraceMissingFile

    @Test
    @DisplayName("Service handles empty JSON request line (null object)")
    void testEmptyJsonRequest() throws Exception {
        Gson gson = PyTutorSerializer.getGson(false);
        String ndjson = "null\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        StringWriter out = new StringWriter();

        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(in, out);
        } // try

        String[] lines = out.toString().trim().split("\\R");
        assertThat(lines).hasSize(1);
        BatchJobResponse resp = gson.fromJson(lines[0], BatchJobResponse.class);
        assertThat(resp.id()).isNull();
        assertThat(resp.result().status()).isEqualTo("failed");
    } // testEmptyJsonRequest

    @Test
    @DisplayName("App batch-trace subcommand reads from System.in when --input is omitted")
    void testAppBatchTraceViaStdin() throws Exception {
        Gson gson = PyTutorSerializer.getGson(false);
        BatchJobRequest req = new BatchJobRequest(
                "stdin-1", SIMPLE_SOURCE, "pytutor", null, List.of("4"),
                false, false, false, false, false, "fqn", null, null);

        java.io.InputStream origIn = System.in;
        PrintStream origOut = System.out;
        ByteArrayInputStream testIn = new ByteArrayInputStream(
                (gson.toJson(req) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();

        System.setIn(testIn);
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        try {
            App.BatchTrace batchTrace = new App.BatchTrace();
            int exitCode = new picocli.CommandLine(batchTrace).execute();
            assertThat(exitCode).isEqualTo(0);
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
        } // try

        String output = capturedOut.toString(StandardCharsets.UTF_8).trim();
        assertThat(output).isNotEmpty();
        BatchJobResponse resp = gson.fromJson(output, BatchJobResponse.class);
        assertThat(resp.id()).isEqualTo("stdin-1");
        assertThat(resp.result().complete()).isTrue();
    } // testAppBatchTraceViaStdin

    @Test
    @DisplayName("Service handles thread interruption when executing job")
    void testExecuteJobInterrupted() throws Exception {
        BatchJobRequest req = new BatchJobRequest(
                "job-intr", SIMPLE_SOURCE, "pytutor", null, List.of("4"),
                false, false, false, false, false, "fqn", null, null);
        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            Thread.currentThread().interrupt();
            BatchJobResponse resp = service.executeJob(req);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(resp.id()).isEqualTo("job-intr");
            assertThat(resp.result().status()).isEqualTo("stopped");
        } // try
    } // testExecuteJobInterrupted
    @Test
    @DisplayName("Service handles stream exceeding in-flight capacity")
    void testExceedingInFlightCapacity() throws Exception {
        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            ndjson.append("{\"id\":\"job-").append(i).append("\",\"source\":\"public class InFlight")
                    .append(i).append(" { public static void main(String[] args) {} }\"}\n");
        } // for
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.toString().getBytes(StandardCharsets.UTF_8));
        StringWriter out = new StringWriter();

        try (BatchTraceService service = new BatchTraceService(1, 100)) {
            service.processStream(in, out);
        } // try

        String[] lines = out.toString().trim().split("\\R");
        assertThat(lines).hasSize(20);
    } // testExceedingInFlightCapacity

    @Test
    @DisplayName("Service close handles thread interruption during awaitTermination")
    void testCloseInterrupted() throws Exception {
        BatchTraceService service = new BatchTraceService(1, 10);
        var field = BatchTraceService.class.getDeclaredField("executor");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService exec =
                (java.util.concurrent.ExecutorService) field.get(service);
        exec.submit(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                // ignore
            } // try
        });
        Thread.currentThread().interrupt();
        service.close();
        assertThat(Thread.interrupted()).isTrue();
    } // testCloseInterrupted

    @Test
    @DisplayName("readBoundedLine reads lines with various line terminators and handles bounds")
    void testReadBoundedLine() throws Exception {
        // Empty input returns null
        java.io.BufferedReader emptyReader =
                new java.io.BufferedReader(new java.io.StringReader(""));
        assertThat(BatchTraceService.readBoundedLine(emptyReader, 100)).isNull();

        // Standard LF, CRLF, CR-only, and EOF without newline
        String content = "line1\nline2\r\nline3\rline4";
        java.io.BufferedReader reader =
                new java.io.BufferedReader(new java.io.StringReader(content));
        assertThat(BatchTraceService.readBoundedLine(reader, 100)).isEqualTo("line1");
        assertThat(BatchTraceService.readBoundedLine(reader, 100)).isEqualTo("line2");
        assertThat(BatchTraceService.readBoundedLine(reader, 100)).isEqualTo("line3");
        assertThat(BatchTraceService.readBoundedLine(reader, 100)).isEqualTo("line4");
        assertThat(BatchTraceService.readBoundedLine(reader, 100)).isNull();

        // Exceeding limit throws IOException and drains line
        String oversized = "0123456789extra\nnextLine";
        java.io.BufferedReader boundReader =
                new java.io.BufferedReader(new java.io.StringReader(oversized));
        assertThatThrownBy(() -> BatchTraceService.readBoundedLine(boundReader, 5))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("NDJSON record exceeds maximum size");
        assertThat(BatchTraceService.readBoundedLine(boundReader, 100)).isEqualTo("nextLine");

        // Exceeding limit at EOF without newline
        String oversizedAtEof = "0123456789extra";
        java.io.BufferedReader eofReader =
                new java.io.BufferedReader(new java.io.StringReader(oversizedAtEof));
        assertThatThrownBy(() -> BatchTraceService.readBoundedLine(eofReader, 5))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("NDJSON record exceeds maximum size");
    } // testReadBoundedLine

    @Test
    @DisplayName("Service close triggers shutdownNow when awaitTermination times out")
    void testCloseTimeout() throws Exception {
        BatchTraceService service = new BatchTraceService(1, 10);
        var field = BatchTraceService.class.getDeclaredField("executor");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean shutdownNowCalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.ExecutorService mockExec =
                new java.util.concurrent.AbstractExecutorService() {
                    @Override public void shutdown() {}
                    @Override public List<Runnable> shutdownNow() {
                        shutdownNowCalled.set(true);
                        return List.of();
                    } // shutdownNow
                    @Override public boolean isShutdown() { return true; }
                    @Override public boolean isTerminated() { return false; }
                    @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) {
                        return false;
                    } // awaitTermination
                    @Override public void execute(Runnable cmd) {}
                };
        field.set(service, mockExec);
        service.close();
        assertThat(shutdownNowCalled.get()).isTrue();
    } // testCloseTimeout

    @Test
    @DisplayName("Service close handles secondary InterruptedException during awaitTermination")
    void testCloseSecondaryInterrupted() throws Exception {
        BatchTraceService service = new BatchTraceService(1, 10);
        var field = BatchTraceService.class.getDeclaredField("executor");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService mockExec =
                new java.util.concurrent.AbstractExecutorService() {
                    @Override public void shutdown() {}
                    @Override public List<Runnable> shutdownNow() { return List.of(); }
                    @Override public boolean isShutdown() { return true; }
                    @Override public boolean isTerminated() { return false; }
                    @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u)
                            throws InterruptedException {
                        throw new InterruptedException("simulated interrupt");
                    } // awaitTermination
                    @Override public void execute(Runnable cmd) {}
                };
        field.set(service, mockExec);
        service.close();
        assertThat(Thread.interrupted()).isTrue();
    } // testCloseSecondaryInterrupted
}
