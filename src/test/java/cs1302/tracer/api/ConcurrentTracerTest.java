package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(300)
class ConcurrentTracerTest {

    private static final int THREADS = 6;
    // Generous trace-phase budget so host load from six guests does not cause a timeout stop.
    private static final TraceLimits LIMITS = TraceLimits.instructorDefaults().toBuilder()
            .timeoutMillis(60_000).snapshots(1_000).build();

    @Test
    void concurrentTracesMatchSequentialBaselines() throws Exception {
        List<TraceRequest> requests = IntStream.range(0, 2 * THREADS)
                .mapToObj(ConcurrentTracerTest::plainRequest).toList();
        List<TraceOutcome> sequential = requests.stream().map(r -> Tracer.trace(r, true)).toList();
        Set<Path> before = compilationDirectories();
        List<TraceOutcome> concurrent = concurrently(requests.size(), i -> Tracer.trace(requests.get(i), true));
        for (int i = 0; i < requests.size(); i++) {
            TraceOutcome expected = sequential.get(i);
            TraceOutcome actual = concurrent.get(i);
            assertThat(expected.complete()).as(expected.json()).isTrue();
            assertThat(actual.complete()).as(actual.json()).isTrue();
            assertThat(actual.status()).isEqualTo("completed");
            assertThat(actual.stdout()).isEqualTo(Integer.toString(i * 2 + 1));
            assertThat(JsonParser.parseString(actual.traceJson()))
                    .isEqualTo(JsonParser.parseString(expected.traceJson()));
            JsonObject expectedEnvelope = JsonParser.parseString(expected.json()).getAsJsonObject();
            JsonObject actualEnvelope = JsonParser.parseString(actual.json()).getAsJsonObject();
            expectedEnvelope.remove("counters");
            actualEnvelope.remove("counters");
            assertThat(actualEnvelope).isEqualTo(expectedEnvelope);
        }
        assertThat(compilationDirectories()).isEqualTo(before);
    }

    @Test
    void concurrentTracesAndDiscoveriesKeepInputLambdasAndFailuresJobScoped() throws Exception {
        int jobs = 2 * THREADS; // multiple of THREADS so every barrier round trips
        int broken = jobs - 1;  // 11 % 3 == 2, so it is a discovery job
        List<BreakpointDiscovery> expectedDiscovery = IntStream.range(0, jobs)
                .mapToObj(i -> Tracer.discoverBreakpoints(plain(i))).toList();
        Set<Path> before = compilationDirectories();
        List<Object> results = concurrently(jobs, i -> switch (i % 3) {
            case 0, 1 -> Tracer.trace(richRequest(i)); // jobs 3 and 7 are multithread (dedicated guests)
            default -> Tracer.discoverBreakpoints(i == broken ? "public class Main { broken }" : plain(i));
        });
        for (int i = 0; i < jobs; i++) {
            if (results.get(i) instanceof TraceOutcome outcome) {
                assertThat(outcome.complete()).as(outcome.json()).isTrue();
                assertThat(outcome.stdout()).isEqualTo(marker(i) + ":" + (i + 1));
                assertThat(outcome.traceJson()).contains("return x + " + i + ";");
                for (int j = 0; j < jobs; j++) {
                    if (j != i) {
                        assertThat(outcome.json()).doesNotContain(marker(j));
                    }
                }
            } else if (i == broken) {
                BreakpointDiscovery failed = (BreakpointDiscovery) results.get(i);
                assertThat(failed.breakpoints()).isEmpty();
                assertThat(failed.diagnostics()).isNotEmpty();
            } else {
                assertThat(results.get(i)).isEqualTo(expectedDiscovery.get(i));
                assertThat(((BreakpointDiscovery) results.get(i)).diagnostics()).isEmpty();
            }
        }
        assertThat(compilationDirectories()).isEqualTo(before);
    }

    private static String marker(int job) {
        return "@job#" + job + "@";
    }

    /** Heap-free source with a job-specific value; deterministic like TraceJobContractTest's program. */
    private static String plain(int job) {
        return """
                public class Main {
                    static int answer;
                    public static void main(String[] args) {
                        args = null;
                        int value = %d;
                        answer = value * 2 + 1;
                        System.out.print(answer);
                    }
                }
                """.formatted(job);
    }

    private static TraceRequest plainRequest(int job) {
        return TraceRequest.builder(plain(job))
                .format(job % 2 == 0 ? TraceFormat.PYTUTOR : TraceFormat.MODERN)
                .inspection(job % 3 == 0 ? InspectionPolicy.FIELDS : InspectionPolicy.TRUSTED)
                .removeMainArgs(true).limits(LIMITS).build();
    }

    /** Job-specific stdin, static lambda, and generic call; every fourth job uses a dedicated multithread guest. */
    private static TraceRequest richRequest(int job) {
        boolean multithread = job % 4 == 3;
        String body = multithread
                ? "Thread worker = new Thread(() -> System.out.print(line + \":\" + op.applyAsInt(1)));"
                        + " worker.start(); worker.join();"
                : "System.out.print(line + \":\" + id(op).applyAsInt(1));";
        String source = """
                public class Main {
                    static java.util.function.IntUnaryOperator op = x -> x + %d;
                    static <T> T id(T value) { return value; }
                    public static void main(String[] args) throws Exception {
                        String line = new java.util.Scanner(System.in).nextLine();
                        %s
                    }
                }
                """.formatted(job, body);
        return TraceRequest.builder(source).stdin(marker(job) + "\n")
                .format(multithread || job % 2 == 1 ? TraceFormat.MODERN : TraceFormat.PYTUTOR)
                .multithread(multithread).limits(LIMITS).build();
    }

    /** Runs tasks in barrier-aligned waves on reused pool threads; tasks must be a multiple of THREADS. */
    private static <T> List<T> concurrently(int tasks, IntFunction<T> call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            List<Callable<T>> work = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                work.add(() -> {
                    start.await(60, TimeUnit.SECONDS);
                    return call.apply(index);
                });
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(work, 240, TimeUnit.SECONDS)) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static Set<Path> compilationDirectories() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("code-tracer"))
                    .collect(Collectors.toSet());
        }
    }
}
