package cs1302.tracer.batch;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonParser;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
class ConcurrentBatchTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("try") // Explicit producer close signals EOF before awaiting stream completion.
    void streamsInConfiguredOrderBeforeInputCloses(boolean completionOrder) throws Exception {
        var secondFinished = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstOutput = new CountDownLatch(1);
        java.util.function.Function<BatchJobRequest, BatchJobResponse> runner = request -> {
            if (request.id().equals("slow")) {
                try {
                    assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            } else {
                secondFinished.countDown();
            }
            return new BatchJobResponse(request.id(), TraceResult.failed("modern", "test", "test"));
        };
        var output = new StringWriter() {
            @Override public void flush() {
                if (getBuffer().length() > 0) {
                    firstOutput.countDown();
                }
            }
        };
        try (var service = new BatchTraceService(2, 10, 2, completionOrder, runner);
                var producer = new PipedOutputStream();
                var input = new PipedInputStream(producer);
                var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> {
                service.processStream(input, output);
                return null;
            });
            producer.write("{\"id\":\"slow\"}\n{\"id\":\"fast\"}\n".getBytes(StandardCharsets.UTF_8));
            producer.flush();
            assertThat(secondFinished.await(10, TimeUnit.SECONDS)).isTrue();
            if (completionOrder) {
                assertThat(firstOutput.await(10, TimeUnit.SECONDS)).isTrue();
            } else {
                assertThat(output.toString()).isEmpty();
            }
            releaseFirst.countDown();
            assertThat(firstOutput.await(10, TimeUnit.SECONDS)).isTrue();
            producer.close();
            running.get(10, TimeUnit.SECONDS);
            var ids = output.toString().lines().map(line ->
                    JsonParser.parseString(line).getAsJsonObject().get("id").getAsString()).toList();
            assertThat(ids).containsExactlyElementsOf(completionOrder
                    ? List.of("fast", "slow") : List.of("slow", "fast"));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void fixtureRunsMixedCaptureModesInIndependentGuests() throws Exception {
        try (var service = new BatchTraceService(2, 10, 2, false);
                var input = Files.newInputStream(Path.of("examples/concurrent-batch/jobs.ndjson"))) {
            var output = new StringWriter();
            service.processStream(input, output);
            var responses = output.toString().lines()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
            assertThat(responses).hasSize(3);
            assertThat(responses.stream().map(r -> r.get("id").getAsString()).toList())
                    .containsExactly("workers", "counter", "quick");
            assertThat(responses).allSatisfy(r -> assertThat(r.getAsJsonObject("result")
                    .get("complete").getAsBoolean()).as("%s", r).isTrue());
            assertThat(responses.get(0).getAsJsonObject("result").get("stdout").getAsString())
                    .isEqualTo("2\n2\njoined\n");
            assertThat(responses.get(1).getAsJsonObject("result").get("stdout").getAsString())
                    .isEqualTo("6\n");
            assertThat(responses.get(2).getAsJsonObject("result").get("stdout").getAsString())
                    .isEqualTo("quick\n");
        }
    }

    @Test
    void completionOrderReleasesAdmissionSlotsBeforeSlowEarlierJobsFinish() throws Exception {
        var thirdStarted = new CountDownLatch(1);
        java.util.function.Function<BatchJobRequest, BatchJobResponse> runner = request -> {
            if (request.id().equals("first")) {
                try {
                    assertThat(thirdStarted.await(10, TimeUnit.SECONDS))
                            .as("a completed second job must free an admission slot").isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            } else if (request.id().equals("third")) {
                thirdStarted.countDown();
            }
            return new BatchJobResponse(request.id(), TraceResult.failed("modern", "test", "test"));
        };
        var input = new ByteArrayInputStream(("{\"id\":\"first\"}\n"
                + "{\"id\":\"second\"}\n{\"id\":\"third\"}\n").getBytes(StandardCharsets.UTF_8));
        var output = new StringWriter();
        try (var service = new BatchTraceService(2, 10, 2, true, runner)) {
            service.processStream(input, output);
        }
        var ids = output.toString().lines().map(line ->
                JsonParser.parseString(line).getAsJsonObject().get("id").getAsString()).toList();
        assertThat(ids).containsExactlyInAnyOrder("first", "second", "third");
        assertThat(ids.getFirst()).isEqualTo("second");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsOutputFailuresInsteadOfSilentlyDroppingResponses(boolean completionOrder) throws Exception {
        var broken = new Writer() {
            @Override public void write(char[] buffer, int offset, int count) throws IOException {
                throw new IOException("output disconnected");
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        try (var service = new BatchTraceService(1, 10, 1, completionOrder,
                request -> new BatchJobResponse(request.id(), TraceResult.failed("modern", "test", "test")))) {
            var input = new ByteArrayInputStream("{\"id\":\"one\"}\n".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.processStream(input, broken))
                    .isInstanceOf(IOException.class).hasMessageContaining("Failed to write batch response");
        }
    }

    @Test
    void multithreadBatchRejectsPythonTutorWithoutLaunchingAGuest() {
        try (var worker = new BatchTraceWorker(10, () -> {
            throw new AssertionError("validation must precede launch");
        })) {
            var request = new BatchJobRequest("invalid", "ignored", "pytutor", "", null,
                    false, false, false, false, false, null, null, null, true);
            var result = worker.execute(request).result();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.phase()).isEqualTo("validation");
            assertThat(result.diagnostics()).contains("multithread requires modern format");
        }
    }

    @Test
    void multithreadBatchDoesNotDiscoverNeighboringAsts() {
        var source = """
                package cs1302.tracer;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("submitted");
                    }
                }
                """;
        try (var worker = new BatchTraceWorker(10)) {
            var request = new BatchJobRequest("isolated", source, "modern", "", null,
                    false, false, false, false, false, null, null, null, true);
            var result = worker.execute(request).result();
            assertThat(result.complete()).as("%s", result).isTrue();
            assertThat(result.stdout()).isEqualTo("submitted\n");
            assertThat(result.diagnostics()).anyMatch(message -> message.startsWith("FIELDS inspection"));
        }
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(InspectionPolicy.class)
    void multithreadBatchRequiresExplicitlySubmittedDependencies(InspectionPolicy inspection)
            throws Exception {
        var source = """
                // --- Main.java ---
                package cs1302.tracer.execution;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(InspectionPolicy.FIELDS);
                    }
                }
                """;
        var dependency = Files.readString(Path.of(
                "src/main/java/cs1302/tracer/execution/InspectionPolicy.java"));
        try (var worker = new BatchTraceWorker(10)) {
            for (boolean submitted : List.of(false, true)) {
                var request = new BatchJobRequest("isolated",
                        source + (submitted ? "// --- InspectionPolicy.java ---\n" + dependency : ""),
                        "modern", "", null, false, false, false, false, false,
                        null, null, inspection, true);
                var result = worker.execute(request).result();
                assertThat(result.complete()).as("%s", result).isEqualTo(submitted);
                assertThat(result.diagnostics()).anyMatch(message -> message.startsWith("FIELDS inspection"));
                if (submitted) {
                    assertThat(result.stdout()).isEqualTo("FIELDS\n");
                } else {
                    assertThat(result.phase()).isEqualTo("compile");
                }
            }
        }
    }

    @Test
    void stoppedMultithreadBatchKeepsItsThreadAwareHistory() throws Exception {
        var source = Files.readString(Path.of("examples/example34/Driver.java"));
        var limits = new cs1302.tracer.execution.TraceLimits(
                10000, 3, 1048576, 1000, 10000, 1048576, 1048576, 1, 16, 1024, 1048576);
        try (var worker = new BatchTraceWorker(10)) {
            var request = new BatchJobRequest("partial", source, "modern", "", null,
                    false, false, false, false, false, null, limits, null, true);
            var result = worker.execute(request).result();
            assertThat(result.stopReason()).isEqualTo("snapshot_limit");
            var trace = (cs1302.tracer.model.modern.Trace) result.trace();
            assertThat(trace.steps()).hasSize(3).allSatisfy(step -> {
                assertThat(step.threads()).isNotEmpty();
                assertThat(step.triggeringThreadId()).isNotNull();
            });
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BatchTraceService(0, 10, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchTraceService(1, 0, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchTraceService(1, 10, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
