package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.trace.DebugTraceHelper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoundedExecutionTest {
    @TempDir Path directory;

    JsonObject trace(String body, String... options) throws Exception {
        Path source = directory.resolve("Main.java");
        Files.writeString(source, "public class Main {\n public static void main(String[] args) {\n"
                + body + "\n }\n}\n");
        List<String> command = new ArrayList<>(List.of("cs1302.tracer.App", "trace",
                "--result-envelope", "--timeout-ms", "2500", "--inspection", "FIELDS",
                "-i", source.toString()));
        if (List.of(options).contains("--inspection")) {
            int index = command.indexOf("--inspection");
            command.remove(index);
            command.remove(index);
        }
        command.addAll(List.of(options));
        return run(command);
    }

    JsonObject run(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", System.getProperty("java.class.path")));
        command.addAll(arguments);
        Path output = directory.resolve("out.json");
        Path errors = directory.resolve("err.txt");
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(errors.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS))
                    .as("subprocess deadline; stderr=%s", Files.readString(errors)).isTrue();
            String json = Files.readString(output);
            assertThat(json).as("stderr=%s", Files.readString(errors)).isNotBlank();
            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
            assertThat(process.exitValue()).isEqualTo(result.get("complete").getAsBoolean() ? 0
                    : result.get("status").getAsString().equals("stopped") ? 3 : 1);
            return result;
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminatesLoopWithoutBreakpointHits() throws Exception {
        Path pidFile = directory.resolve("guest.pid");
        String pidPath = pidFile.toString().replace("\\", "\\\\");
        var result = trace("try { java.nio.file.Files.writeString(java.nio.file.Path.of(\""
                + pidPath + "\"), Long.toString(ProcessHandle.current().pid())); }"
                + " catch (Exception e) { throw new RuntimeException(e); } while (true) {}", "-b", "999");
        long pid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("guest must terminate before the CLI finishes").isFalse();
        assertThat(result.get("stopReason").getAsString()).isEqualTo("timeout");
        assertThat(result.get("complete").getAsBoolean()).isFalse();
    }

    @Test
    void capsOutputBeforeRetainingIt() throws Exception {
        var result = trace("while (true) { System.out.print(\"abcdef\"); }",
                "--max-output-bytes", "64");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("output_limit");
        assertThat(result.getAsJsonObject("counters").get("stdoutBytes").getAsLong()).isEqualTo(64);
        assertThat(result.get("stdout").getAsString()).hasSize(64);
        assertThat(result.getAsJsonObject("trace").getAsJsonArray("trace")).isEmpty();
    }

    @Test
    void returnsCompleteSnapshotsAtSnapshotLimit() throws Exception {
        var result = trace("int x = 0;\nwhile (true) {\n x++;\n}", "-a", "--max-snapshots", "3");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("snapshot_limit");
        assertThat(result.getAsJsonObject("trace").getAsJsonArray("trace")).hasSize(3);
        assertThat(result.getAsJsonObject("counters").get("snapshotsCaptured").getAsInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"--max-elements,10,element_limit", "--max-heap-objects,1,heap_limit",
            "--max-trace-bytes,100,trace_limit"})
    void boundsSnapshotExtraction(String option, String value, String reason) throws Exception {
        var result = trace("int[] data = new int[100];\nSystem.out.println(data.length);", option, value);
        assertThat(result.get("stopReason").getAsString()).isEqualTo(reason);
        assertThat(result.getAsJsonObject("counters").get("droppedSnapshots").getAsInt()).isEqualTo(1);
    }

    @Test
    void boundsTotalRetainedTraceAcrossManySmallSnapshots() throws Exception {
        var result = trace("int x = 0;\nwhile (true) {\nx++;\n}",
                "-a", "--max-trace-bytes", "12000");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("trace_limit");
        assertThat(result.getAsJsonObject("counters").get("snapshotsCaptured").getAsInt())
                .isGreaterThan(1);
        assertThat(result.getAsJsonObject("counters").get("retainedBytes").getAsLong())
                .isLessThanOrEqualTo(12000);
    }

    @Test
    void latestBreakpointModeDoesNotRetainAllHits() throws Exception {
        var result = trace("int x = 0;\nfor (int i=0; i<15; i++) {\nx++;\n}",
                "-b", "5", "--max-trace-bytes", "12000");
        assertThat(result.get("complete").getAsBoolean()).isTrue();
        assertThat(result.getAsJsonObject("counters").get("snapshotsCaptured").getAsInt())
                .isEqualTo(15);
        assertThat(result.getAsJsonObject("counters").get("snapshotsRetained").getAsInt())
                .isEqualTo(1);
    }

    @Test
    void returnsCompileDiagnosticsAndUnavailableTrace() throws Exception {
        var result = trace("int x = false;");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("compile_error");
        assertThat(result.get("trace").isJsonNull()).isTrue();
        assertThat(result.getAsJsonArray("diagnostics").toString()).contains("Compilation");
    }

    @Test
    void reportsGuestException() throws Exception {
        var result = trace("throw new IllegalArgumentException(\"guest failure\");");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("guest_exception");
    }

    @Test
    void reportsNonzeroGuestExitWithoutClaimingSuccessfulCompletion() throws Exception {
        var result = trace("System.exit(7);");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("guest_exit");
        assertThat(result.get("status").getAsString()).isEqualTo("failed");
        assertThat(result.getAsJsonObject("counters").get("guestExitCode").getAsInt()).isEqualTo(7);
    }

    @Test
    void permitsNormalCompletionInBothFormats() throws Exception {
        for (String format : List.of("modern", "pytutor")) {
            var result = trace("int x = 42;\nSystem.out.println(x);", "-f", format);
            assertThat(result.get("complete").getAsBoolean()).isTrue();
            assertThat(result.getAsJsonObject("trace")).isNotNull();
            assertThat(result.get("stdout").getAsString()).isEqualTo("42\n");
        }
    }

    @Test
    void rejectsOversizedSourceBeforeCompilation() throws Exception {
        var result = trace("int x = 1;", "--max-source-bytes", "8");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("source_limit");
        assertThat(result.get("phase").getAsString()).isEqualTo("source");
    }
    @Test
    void capsSourceFileCountBeforeParsing() throws Exception {
        Path source = directory.resolve("stream.java");
        Files.writeString(source, "// --- A.java ---\ninvalid syntax\n"
                + "// --- B.java ---\ninvalid syntax\n");
        var result = run(List.of("cs1302.tracer.App", "trace", "--result-envelope",
                "--max-source-files", "1", "-i", source.toString()));
        assertThat(result.get("stopReason").getAsString()).isEqualTo("source_file_limit");
    }

    @Test
    void restrictiveInspectionDoesNotInvokeCollectionOverrides() throws Exception {
        var result = trace("""
                java.util.ArrayList<String> values = new java.util.ArrayList<>() {
                    public Object[] toArray() { while (true) {} }
                };
                values.add("hello");
                """);
        assertThat(result.get("complete").getAsBoolean()).isTrue();
        assertThat(result.getAsJsonArray("diagnostics").toString()).contains("raw fields");
    }

    @Test
    void snapshotFailurePreservesEarlierCompleteStates() throws Exception {
        var result = trace("int x = 1;\nint[] values = new int[100];\nx++;",
                "-a", "--max-elements", "20");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("element_limit");
        assertThat(result.getAsJsonObject("trace").getAsJsonArray("trace")).isNotEmpty();
        assertThat(result.getAsJsonObject("counters").get("droppedSnapshots").getAsInt()).isEqualTo(1);
    }

    @Test
    void restrictiveInspectionDoesNotInvokeCustomFlush() throws Exception {
        var result = trace("""
                System.setOut(new java.io.PrintStream(System.out) {
                    public void flush() { while (true) {} }
                });
                int answer = 42;
                """);
        assertThat(result.get("complete").getAsBoolean()).isTrue();
    }

    @Test
    void watchdogInterruptsBlockedGuestInspection() throws Exception {
        var result = trace("""
                System.setOut(new java.io.PrintStream(System.out) {
                    public void flush() { while (true) {} }
                });
                int answer = 42;
                """, "--inspection", "TRUSTED");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("timeout");
    }

    @Test
    void cancellationStopsGuestAndReleasesSession() throws Exception {
        run(List.of(CancelProbe.class.getName()));
    }

    public static class CancelProbe {
        public static void main(String[] args) throws Exception {
            String source = "public class Main { public static void main(String[] a) { while(true) {} } }";
            try (var compiled = CompilationHelper.compile(source);
                    var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
                session.phase("trace");
                Thread canceller = Thread.ofPlatform().daemon().start(() -> {
                    try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                    session.cancel();
                });
                Throwable failure = null;
                try {
                    DebugTraceHelper.trace(compiled, List.of(999),
                            List.of(com.github.javaparser.StaticJavaParser.parse(source)));
                } catch (Exception caught) { failure = caught; }
                var result = session.result("pytutor", Map.of("trace", List.of()), failure);
                if (!"cancelled".equals(result.stopReason())) { throw new AssertionError(result); }
                System.out.println(new GsonBuilder().serializeNulls().create().toJson(result));
                canceller.join(2000);
            }
            if (TraceSession.current() != null) { throw new AssertionError("Session leaked"); }
            System.exit(3);
        }
    }

}
