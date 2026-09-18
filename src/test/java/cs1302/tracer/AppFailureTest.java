package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.execution.*;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppFailureTest {
    @Test
    void lexicalErrorsAreRejectedWithAndWithoutSymbolSolver() {
        var trace = new App.Trace();
        assertThatThrownBy(() -> trace.parseSource("/*"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Failed to parse Java source");
        assertThatThrownBy(() -> trace.parseSource("/*", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Failed to parse Java source with symbol solver");
    }

    @Test
    void missingGuestInputFileAndUnsupportedInspectionAreReported(@TempDir Path directory) {
        var trace = new App.Trace();
        new picocli.CommandLine(trace).parseArgs("--stdin-file", directory.resolve("absent").toString());
        assertThatThrownBy(trace::resolveGuestStdin).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read stdin file").hasCauseInstanceOf(IOException.class);
        var invalid = new App.Trace();
        new picocli.CommandLine(invalid).parseArgs("--inspection", "FIELDS");
        var status = new java.util.concurrent.atomic.AtomicInteger();
        invalid.exitHandler = status::set;
        invalid.run();
        assertThat(status.get()).isEqualTo(2);
    }

    @Test
    void invalidBreakpointOptionFailsFastWithExitCode2() {
        var invalidBp = new App.Trace();
        new picocli.CommandLine(invalidBp).parseArgs("-b", "invalid");
        var bpStatus = new java.util.concurrent.atomic.AtomicInteger();
        invalidBp.exitHandler = bpStatus::set;
        invalidBp.run();
        assertThat(bpStatus.get()).isEqualTo(2);
    }

    private static JsonObject envelope(App.Trace trace, String... options) {
        var status = new java.util.concurrent.atomic.AtomicInteger();
        trace.exitHandler = status::set;
        var arguments = new ArrayList<String>(List.of("--result-envelope"));
        arguments.addAll(List.of(options));
        new picocli.CommandLine(trace).parseArgs(arguments.toArray(String[]::new));
        InputStream oldInput = System.in;
        PrintStream oldOutput = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("public class C {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(bytes));
            trace.run();
        } finally { System.setIn(oldInput); System.setOut(oldOutput); }
        JsonObject result = JsonParser.parseString(bytes.toString()).getAsJsonObject();
        assertThat(status.get()).isEqualTo(result.get("status").getAsString().equals("stopped") ? 3 : 1);
        return result;
    }

    @Test
    void interruptedExecutionCancelsEnvelopeAndPreservesInterrupt() {
        var trace = new App.Trace() {
            @Override List<ExecutionSnapshot> executeBoundedSource(String source, TraceSession session,
                    TraceLimits limits, String guestStdin) throws Exception {
                throw new InterruptedException("interrupted guest");
            }
        };
        try {
            var result = envelope(trace, "--accumulate-breakpoints");
            assertThat(result.get("stopReason").getAsString()).isEqualTo("cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void serializationFailureProducesAFailedEnvelope() {
        var trace = new App.Trace() {
            @Override List<ExecutionSnapshot> executeBoundedSource(String source, TraceSession session,
                    TraceLimits limits, String guestStdin) { return List.of(); }
            @Override Object boundedPayload(String source, List<ExecutionSnapshot> snapshots, String guestStdin) {
                throw new IllegalStateException("serializer unavailable");
            }
        };
        var result = envelope(trace);
        assertThat(result.get("status").getAsString()).isEqualTo("failed");
        assertThat(result.get("phase").getAsString()).isEqualTo("serialize");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("tracer_error");
        assertThat(result.get("trace").isJsonNull()).isTrue();
    }

    @Test
    void discoveryIgnoresUnreadableUnreferencedSources(@TempDir Path directory) throws Exception {
        var trace = new App.Trace();
        var discover = App.Trace.class.getDeclaredMethod("discoverAllCompilationUnits", List.class, Optional.class, Optional.class);
        discover.setAccessible(true);
        Files.write(directory.resolve("Invalid.java"), new byte[] {(byte) 0xff});
        Files.writeString(directory.resolve("notes.txt"), "not java");
        assertThat(discover.invoke(trace, List.of(), Optional.of(directory), Optional.empty())).isEqualTo(List.of());
        assertThat(discover.invoke(trace, List.of(), Optional.of(directory.resolve("missing")), Optional.empty())).isEqualTo(List.of());
        assertThat(trace.getInputPath()).isEmpty();
    }

    @Test
    void breakpointLookupFallsBackToFilename() throws Exception {
        var method = App.ListBreakpoints.class.getDeclaredMethod("findValidLinesForFile", Map.class, CompilationHelper.SourceFile.class);
        method.setAccessible(true);
        var command = new App.ListBreakpoints();
        var source = new CompilationHelper.SourceFile("src/C.java", "", new CompilationUnit());
        var lines = new LinkedHashMap<String, Set<Integer>>();
        lines.put("Other.java", Set.of(1));
        assertThat(method.invoke(command, lines, source)).isEqualTo(Set.of());
        lines.put("pkg/C.java", Set.of(2));
        assertThat(method.invoke(command, lines, source)).isEqualTo(Set.of(2));
        assertThat(method.invoke(command, Map.of(), source)).isEqualTo(Set.of());
    }

    @Test
    void ordinaryModernTraceAcceptsExplicitChronologicalLines() {
        var result = AppTest.executeCommand(App.Trace::new,
                "public class C { public static void main(String[] a) { int n = 1; } }", "-f", "modern", "-a", "-b", "1");
        assertThat(result).isPresent();
        assertThat(JsonParser.parseString(result.orElseThrow()).getAsJsonObject().getAsJsonArray("steps")).isNotEmpty();
    }
}
