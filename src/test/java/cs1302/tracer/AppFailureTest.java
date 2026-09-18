package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.execution.*;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.Snapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppFailureTest {
    @Test void qualifiedSelectorsRejectMixedAndNonEnvelopeModesBeforeReadingInput() {
        var status = new java.util.concurrent.atomic.AtomicInteger();
        for (String[] args : List.of(
                new String[] {"--breakpoint-at", "p/Helper.java:4"},
                new String[] {"--result-envelope", "--breakpoint-at", "p/Helper.java:4", "-b", "4"},
                new String[] {"--result-envelope", "--breakpoint-at", "invalid"})) {
            var trace = new App.Trace();
            trace.exitHandler = status::set;
            new picocli.CommandLine(trace).parseArgs(args);
            status.set(0);
            trace.run();
            assertThat(status.get()).isEqualTo(2);
        }
    }

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
            @Override List<Snapshot> executeBoundedSource(String source, TraceSession session,
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
            @Override List<Snapshot> executeBoundedSource(String source, TraceSession session,
                    TraceLimits limits, String guestStdin) { return List.of(); }
            @Override Object boundedPayload(String source, List<Snapshot> snapshots, String guestStdin) {
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
    void lazySerializationFailureReplacesTheSpoolWithAFailedEnvelope() {
        var trace = new App.Trace() {
            @Override List<Snapshot> executeBoundedSource(String source, TraceSession session,
                    TraceLimits limits, String guestStdin) { return List.of(); }
            @Override Object boundedPayload(String source, List<Snapshot> snapshots, String guestStdin) {
                return new cs1302.tracer.serialize.StepView<>(2, index -> {
                    if (index == 1) throw new IllegalStateException("bad lazy step");
                    return "first step must not escape the spool";
                });
            }
        };
        var result = envelope(trace);
        assertThat(result.get("status").getAsString()).isEqualTo("failed");
        assertThat(result.get("phase").getAsString()).isEqualTo("serialize");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("tracer_error");
        assertThat(result.get("trace").isJsonNull()).isTrue();
    }

    @Test
    void unavailableSpoolStorageReportsAnIoFailure(@TempDir Path directory) throws Exception {
        String previous = System.getProperty("java.io.tmpdir");
        Path occupied = Files.writeString(directory.resolve("file"), "not a directory");
        var emit = App.Trace.class.getDeclaredMethod("emitResult", TraceSession.class,
                cs1302.tracer.execution.TraceResult.class);
        emit.setAccessible(true);
        try (var session = new TraceSession(TraceLimits.unlimited(),
                cs1302.tracer.execution.InspectionPolicy.TRUSTED, true)) {
            System.setProperty("java.io.tmpdir", occupied.toString());
            assertThatThrownBy(() -> emit.invoke(new App.Trace(), session,
                    session.result("modern", null, null)))
                    .hasCauseInstanceOf(java.io.UncheckedIOException.class);
        } finally {
            System.setProperty("java.io.tmpdir", previous);
        }
    }

    @Test
    void discoveryIgnoresUnreadableUnreferencedSources(@TempDir Path directory) throws Exception {
        var trace = new App.Trace();
        var discover = App.Trace.class.getDeclaredMethod("discoverAllCompilationUnits", List.class, Optional.class, Optional.class, Set.class);
        discover.setAccessible(true);
        Files.write(directory.resolve("Invalid.java"), new byte[] {(byte) 0xff});
        Files.writeString(directory.resolve("notes.txt"), "not java");
        assertThat(discover.invoke(trace, List.of(), Optional.of(directory), Optional.empty(), Set.of())).isEqualTo(List.of());
        assertThat(discover.invoke(trace, List.of(), Optional.of(directory.resolve("missing")), Optional.empty(), Set.of())).isEqualTo(List.of());
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
    @Test void inputOptionsConflictBeforeOpeningFiles() {
        var trace = new App.Trace();
        new picocli.CommandLine(trace).parseArgs("--stdin", "text", "--stdin-file", "absent");
        assertThatThrownBy(trace::resolveGuestStdin).isInstanceOf(IllegalArgumentException.class);
        var status = new java.util.concurrent.atomic.AtomicInteger();
        trace.exitHandler = status::set;
        trace.run();
        assertThat(status.get()).isEqualTo(2);
    }

    @Test void discoveryReadsOnlyCompiledSourcesAndRejectsEscapingPaths(@TempDir Path root)
            throws Exception {
        var trace = new App.Trace();
        Files.writeString(root.resolve("Needed.java"), "class Needed { final int n=1; }");
        Files.writeString(root.resolve("Unrelated.java"), "this is invalid Java");
        var discover = App.Trace.class.getDeclaredMethod("discoverAllCompilationUnits",
                List.class, Optional.class, Optional.class, Set.class);
        discover.setAccessible(true);
        var units = (List<?>) discover.invoke(trace, List.of(), Optional.of(root),
                Optional.of(root), Set.of("Needed.java"));
        assertThat(units).hasSize(1);
        assertThat(units.getFirst().toString()).contains("Needed");
        assertThatThrownBy(() -> discover.invoke(trace, List.of(), Optional.of(root),
                Optional.of(root), Set.of("../Escapes.java")))
                .hasCauseInstanceOf(IOException.class);
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            assertThat(TraceSession.current()).isSameAs(session);
            new picocli.CommandLine(trace).parseArgs("--max-source-bytes", "1");
            assertThatThrownBy(() -> discover.invoke(trace, List.of(), Optional.of(root),
                    Optional.of(root), Set.of("Needed.java")))
                    .hasCauseInstanceOf(TraceSession.Stopped.class);
        }
    }
    @Test void inputResolutionAlsoWorksOutsideAJob(@TempDir Path root) throws Exception {
        var trace = new App.Trace();
        assertThat(trace.resolveGuestStdin()).isEmpty();
        new picocli.CommandLine(trace).parseArgs("--stdin", "hello");
        assertThat(trace.resolveGuestStdin()).isEqualTo("hello");
        trace = new App.Trace();
        Path file = root.resolve("input.txt");
        Files.writeString(file, "é😀");
        new picocli.CommandLine(trace).parseArgs("--stdin-file", file.toString());
        assertThat(trace.resolveGuestStdin()).isEqualTo("é😀");
    }
}
