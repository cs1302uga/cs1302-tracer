package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TracerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "0, 1, 1", "-1, 1, 1", "1, 0, 1", "1, -1, 1", "1, 1, 0", "1, 1, -1"
    })
    void batchRejectsNonpositiveSettings(int workers, int maxJobsPerWorker, int maxInFlight) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            try (BatchTracer batch = new BatchTracer(workers, maxJobsPerWorker, maxInFlight, false)) {
                assertThat(batch).isNotNull();
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneShotTraceDoesNotDiscoverUnsubmittedNeighbors(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Path neighbor = java.nio.file.Files.createDirectories(root.resolve("ambient"))
                .resolve("Neighbor.java");
        java.nio.file.Files.writeString(neighbor,
                "package ambient; public class Neighbor { public static int value() { return 42; } }");
        java.nio.file.Path consumer = root.resolve("Consumer.java");
        java.nio.file.Files.writeString(consumer, """
                import cs1302.tracer.api.TraceRequest;
                import cs1302.tracer.api.Tracer;
                class Consumer {
                    public static void main(String[] args) {
                        System.out.print(Tracer.trace(new TraceRequest(
                            "package ambient; public class Main { public static void main(String[] args) {"
                            + "System.out.print(Neighbor.value()); } }"
                        )).json());
                    }
                }
                """);
        String classpath = System.getProperty("java.class.path");
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classpath, "-d", root.toString(), consumer.toString())).isZero();
        java.nio.file.Path output = root.resolve("out.json");
        java.nio.file.Path errors = root.resolve("errors.txt");
        Process process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-cp", root + java.io.File.pathSeparator + classpath, "Consumer")
                .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(java.nio.file.Files.readString(errors)).isZero();
            com.google.gson.JsonObject result = com.google.gson.JsonParser.parseString(
                    java.nio.file.Files.readString(output)).getAsJsonObject();
            assertThat(result.get("status").getAsString()).isEqualTo("failed");
            assertThat(result.get("phase").getAsString()).isEqualTo("compile");
            assertThat(result.get("stdout").getAsString()).isEmpty();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void oneShotTraceResolvesOtherSubmittedSources() {
        TraceOutcome outcome = Tracer.trace(new TraceRequest("""
                // --- submitted/Main.java ---
                package submitted;
                public class Main {
                    public static void main(String[] args) {
                        System.out.print(new Parent<Integer>(42).value);
                    }
                }
                // --- submitted/Parent.java ---
                package submitted;
                public class Parent<T> {
                    public T value;
                    public Parent(T value) { this.value = value; }
                }
                """));
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("42");
    }

    @Test
    void callerCanTraceWithoutParserOrSerializerObjects() {
        TraceOutcome outcome = Tracer.trace(new TraceRequest(
                "public class Main { public static void main(String[] args) { System.out.print(42); } }"));
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("42");
        assertThat(outcome.json()).contains("\"status\":\"completed\"");
        assertThat(outcome.traceJson()).contains("\"code\"");
    }
    @Test
    void discoversExecutableLinesWithoutRunningTheProgram() {
        BreakpointDiscovery result = Tracer.discoverBreakpoints("""
                public class Main {
                    public static void main(String[] args) {
                        throw new AssertionError("must not execute");
                    }
                }
                """);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.breakpoints()).contains(new BreakpointLocation("Main.java", 3));
    }
    @Test
    void batchApiKeepsConsecutiveJobOutputIndependent() throws Exception {
        String source = "public class Main { public static void main(String[] args) { System.out.print(42); } }";
        String request = new com.google.gson.Gson().toJson(java.util.Map.of("id", "first", "source", source));
        String second = new com.google.gson.Gson().toJson(java.util.Map.of("id", "second", "source",
                source.replace("print(42)", "print(7)")));
        java.io.StringWriter output = new java.io.StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 100, 2, false)) {
            batch.processStream(new java.io.ByteArrayInputStream((request + "\n" + second + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), output);
        }
        String[] lines = output.toString().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(com.google.gson.JsonParser.parseString(lines[0]).getAsJsonObject().getAsJsonObject("result")
                .get("stdout").getAsString()).isEqualTo("42");
        assertThat(com.google.gson.JsonParser.parseString(lines[1]).getAsJsonObject().getAsJsonObject("result")
                .get("stdout").getAsString()).isEqualTo("7");
    }
    @Test
    void reportsCompilationAndDiscoveryFailuresAsDiagnostics() {
        TraceOutcome failed = Tracer.trace(new TraceRequest("public class Main { broken }"));
        assertThat(failed.complete()).isFalse();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.phase()).isEqualTo("compile");
        assertThat(failed.diagnostics()).isNotEmpty();
        assertThat(failed.traceJson()).isEqualTo("null");
        BreakpointDiscovery discovery = Tracer.discoverBreakpoints("public class Main { broken }");
        assertThat(discovery.breakpoints()).isEmpty();
        assertThat(discovery.diagnostics()).isNotEmpty();
    }

    @Test
    void enforcesSourceBudgetAndWritesDetachedResult() throws Exception {
        TraceRequest request = new TraceRequest(
                "public class Main { public static void main(String[] args) {} }", "", TraceFormat.MODERN,
                java.util.List.of(), false, false, false, false, false, false, TypeStyle.FQN,
                new TraceLimits(0, 0, 0, 0, 0, 0, 1, 0), InspectionPolicy.FIELDS);
        TraceOutcome stopped = Tracer.trace(request);
        assertThat(stopped.status()).isEqualTo("stopped");
        assertThat(stopped.stopReason()).isEqualTo("source_limit");
        java.io.StringWriter writer = new java.io.StringWriter();
        stopped.writeJson(writer);
        assertThat(writer.toString()).isEqualTo(stopped.json());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stopped.counters().put("changed", 1L))
                .isInstanceOf(UnsupportedOperationException.class);
        java.io.Writer closed = java.io.Writer.nullWriter();
        closed.close();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stopped.writeJson(closed))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void modernRequestHonorsInputAndChronologicalSelection() {
        TraceRequest request = new TraceRequest("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.print(new java.util.Scanner(System.in).nextLine());
                    }
                }
                """, "hello\n", TraceFormat.MODERN, java.util.List.of(), true, true, false,
                true, true, true, TypeStyle.SIMPLE, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED);
        TraceOutcome outcome = Tracer.trace(request);
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("hello");
        assertThat(outcome.format()).isEqualTo("modern");
        assertThat(com.google.gson.JsonParser.parseString(outcome.traceJson()).getAsJsonObject()
                .getAsJsonArray("steps").size()).isGreaterThan(1);
    }
}
