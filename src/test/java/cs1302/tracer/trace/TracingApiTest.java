package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import cs1302.tracer.CompilationHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TracingApiTest {
    private static final String SOURCE = """
            public class Main {
              public static void main(String[] args) {
                int sum = 0;
                for (int i = 0; i < 3; i++) {
                  sum += i;
                }
                System.out.println(sum);
              }
            }
            """;

    @Test
    void publicOverloadsRetainTheirDocumentedHistory() throws Exception {
        var ast = StaticJavaParser.parse(SOURCE);
        try (var compiled = CompilationHelper.compile(SOURCE)) {
            var full = DebugTraceHelper.trace(compiled, List.of(5), ast, "");
            assertThat(full.get(5)).hasSize(3);
            var latest = DebugTraceHelper.traceLatest(compiled, List.of(5), List.of(ast));
            assertThat(latest.get(5)).hasSize(1);
            var latestSpecs = DebugTraceHelper.traceLatestWithSpecs(compiled, List.of(BreakpointSpec.of(5)), List.of(ast));
            assertThat(latestSpecs.get(5)).hasSize(1);
            var nullSpecsTrace = DebugTraceHelper.traceWithSpecs(compiled, null, List.of(ast), "");
            assertThat(nullSpecsTrace).containsKey(-1);
            var mismatchedSpecTrace = DebugTraceHelper.traceWithSpecs(
                    compiled,
                    List.of(BreakpointSpec.of("Nonexistent.java", 5), BreakpointSpec.of(5)),
                    List.of(ast),
                    "");
            assertThat(mismatchedSpecTrace.get(5)).hasSize(3);
            var chronological = DebugTraceHelper.traceChronological(compiled, List.of(5), ast, false, "");
            assertThat(chronological).hasSize(3);
            assertThat(chronological).allSatisfy(snapshot ->
                    assertThat(snapshot.stack().getLast().methodLine()).isEqualTo(5));
            var chronoNullSpecs = DebugTraceHelper.traceChronologicalWithSpecs(
                    compiled, null, List.of(ast), false, "");
            assertThat(chronoNullSpecs).isEmpty();

            try (var session = new cs1302.tracer.execution.TraceSession(
                    cs1302.tracer.execution.TraceLimits.unlimited(),
                    cs1302.tracer.execution.InspectionPolicy.TRUSTED, true,
                    false)) {
                assertThat(session.accumulates()).isTrue();
                var latestInSession = DebugTraceHelper.traceLatestWithSpecs(
                        compiled, List.of(BreakpointSpec.of(5)), List.of(ast), "");
                assertThat(latestInSession.get(5)).hasSize(1);
            } // try

            var overlappingSpecsTrace = DebugTraceHelper.traceWithSpecs(
                    compiled,
                    List.of(
                            BreakpointSpec.of(5),
                            BreakpointSpec.of("Main.java", 5),
                            BreakpointSpec.of("Main.java", 999),
                            BreakpointSpec.of(-1)),
                    List.of(ast),
                    "");
            assertThat(overlappingSpecsTrace.get(5)).hasSize(3);
            assertThat(overlappingSpecsTrace).containsKey(-1);
        }
    }

    @Test
    void chronologicalExceptionsRetainTheThrowingFrame() throws Exception {
        String source = """
                public class Main {
                  public static void main(String[] args) {
                    throw new IllegalArgumentException("intentional");
                  }
                }
                """;
        try (var compiled = CompilationHelper.compile(source)) {
            var snapshots = DebugTraceHelper.traceChronological(compiled, List.of(999),
                    StaticJavaParser.parse(source), false, "");
            assertThat(snapshots).isNotEmpty();
            assertThat(snapshots.getLast().stack().getLast().methodLine()).isEqualTo(3);
        }
    }

    @Test
    void emptyAndSentinelBreakpointsCaptureMainExit() throws Exception {
        try (var compiled = CompilationHelper.compile(SOURCE)) {
            for (var lines : List.of(List.<Integer>of(), List.of(-1))) {
                var snapshots = DebugTraceHelper.trace(compiled, lines, List.of(StaticJavaParser.parse(SOURCE)), "");
                assertThat(snapshots).containsKey(-1);
                assertThat(snapshots.get(-1)).hasSize(1);
                assertThat(new String(snapshots.get(-1).getFirst().stdout(), java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("3\n");
            }
        }
    }

    @Test
    void multiFileSameLineBreakpointsRetainedInLatest() throws Exception {
        String fileA = """
                public class Main {
                  public static void main(String[] args) {
                    Helper.doWork();
                    System.out.println("done");
                  }
                }
                """;
        String fileB = """
                public class Helper {
                  public static void doWork() {
                    int x = 42;
                    int y = x + 1;
                  }
                }
                """;
        var astA = StaticJavaParser.parse(fileA);
        var astB = StaticJavaParser.parse(fileB);
        String combined = "// --- Main.java ---\n" + fileA + "\n// --- Helper.java ---\n" + fileB;
        try (var compiled = CompilationHelper.compile(combined)) {
            var specs = List.of(
                    BreakpointSpec.of("Main.java", 4),
                    BreakpointSpec.of("Helper.java", 4));
            var latest = DebugTraceHelper.traceLatestWithSpecs(compiled, specs, List.of(astA, astB), "");
            assertThat(latest.get(4)).hasSize(2);
            assertThat(latest.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Main.java"));
            assertThat(latest.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Helper.java"));

            try (var session = new cs1302.tracer.execution.TraceSession(
                    cs1302.tracer.execution.TraceLimits.unlimited(),
                    cs1302.tracer.execution.InspectionPolicy.TRUSTED, false)) {
                assertThat(session.accumulates()).isFalse();
                var latestInSession = DebugTraceHelper.traceLatestWithSpecs(
                        compiled, specs, List.of(astA, astB), "");
                assertThat(latestInSession.get(4)).hasSize(2);
                assertThat(latestInSession.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Main.java"));
                assertThat(latestInSession.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Helper.java"));
            } // try
        }
    }
}
