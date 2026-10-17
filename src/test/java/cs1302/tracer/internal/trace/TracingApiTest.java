package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.internal.CompilationHelper;
import cs1302.tracer.internal.SourcePreparation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
            var latestSpecs = DebugTraceHelper.traceLatestWithSpecs(compiled, List.of(BreakpointSpec.of(5)),
                    List.of(ast));
            assertThat(latestSpecs.get(5)).hasSize(1);
            var nullSpecsTrace = DebugTraceHelper.traceWithSpecs(compiled, null, List.of(ast), "");
            assertThat(nullSpecsTrace).containsKey(-1);
            var mismatchedSpecTrace = DebugTraceHelper.traceWithSpecs(compiled,
                    List.of(BreakpointSpec.of("Nonexistent.java", 5), BreakpointSpec.of(5)), List.of(ast), "");
            assertThat(mismatchedSpecTrace.get(5)).hasSize(3);
            var chronological = DebugTraceHelper.traceChronological(compiled, List.of(5), ast, false, "");
            assertThat(chronological).hasSize(3);
            assertThat(chronological)
                    .allSatisfy(snapshot -> assertThat(snapshot.stack().getLast().methodLine()).isEqualTo(5));
            var chronoNullSpecs = DebugTraceHelper.traceChronologicalWithSpecs(compiled, null, List.of(ast), false, "");
            assertThat(chronoNullSpecs).isEmpty();

            try (var session = new cs1302.tracer.internal.execution.TraceSession(cs1302.tracer.api.TraceLimits.unlimited(),
                    cs1302.tracer.api.InspectionPolicy.TRUSTED, true, false)) {
                assertThat(session.accumulates()).isTrue();
                var latestInSession = DebugTraceHelper.traceLatestWithSpecs(compiled, List.of(BreakpointSpec.of(5)),
                        List.of(ast), "");
                assertThat(latestInSession.get(5)).hasSize(1);
            } // try

            var overlappingSpecsTrace = DebugTraceHelper.traceWithSpecs(compiled, List.of(BreakpointSpec.of(5),
                    BreakpointSpec.of("Main.java", 5), BreakpointSpec.of("Main.java", 999), BreakpointSpec.of(-1)),
                    List.of(ast), "");
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
            var snapshots = DebugTraceHelper.traceChronological(compiled, List.of(999), StaticJavaParser.parse(source),
                    false, "");
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
    @Timeout(30)
    void multipleParsedSourcesDefaultToEmptyInputAndRetainSourceEvidence() throws Exception {
        String main = """
                public class Main {
                    public static void main(String[] args) throws java.io.IOException {
                        int observed = Helper.readInput();
                        System.out.print(observed);
                    }
                }
                """;
        String helper = """
                public class Helper {
                    public static int readInput() throws java.io.IOException {
                        final int input = System.in.read();
                        return input;
                    }
                }
                """;
        String combined = "// --- Main.java ---\n" + main + "\n// --- Helper.java ---\n" + helper;
        try (CompilationHelper.CompilationResult compiled = CompilationHelper.compile(combined)) {
            List<CompilationUnit> units = List.of(
                    SourcePreparation.parseSource(main, Optional.of(compiled.classPath())),
                    SourcePreparation.parseSource(helper, Optional.of(compiled.classPath())));
            Map<Integer, List<ExecutionSnapshot>> snapshots = DebugTraceHelper.trace(compiled, List.of(4, -1), units);
            assertThat(snapshots.get(4)).hasSize(2);
            assertThat(snapshots.get(4)).extracting(snapshot -> snapshot.sourcePath().orElseThrow())
                    .containsExactly("Helper.java", "Main.java");
            ExecutionSnapshot helperSnapshot = snapshots.get(4).getFirst();
            assertThat(helperSnapshot.stack().getLast().visibleVariables())
                    .filteredOn(field -> field.identifier().equals("input"))
                    .singleElement().satisfies(field -> {
                        assertThat(field.isFinal()).isTrue();
                        assertThat(field.value()).isEqualTo(new TraceValue.Primitive.Integer(-1));
                    });
            assertThat(snapshots.get(-1)).hasSize(1);
            ExecutionSnapshot finished = snapshots.get(-1).getFirst();
            assertThat(new String(finished.stdout(), StandardCharsets.UTF_8)).isEqualTo("-1");
            assertThat(finished.stderr()).isEmpty();
            assertThat(snapshots.values()).allSatisfy(states -> assertThat(states).allSatisfy(snapshot -> {
                assertThat(snapshot.stdinConsumed()).isEmpty();
                assertThat(snapshot.stdinOffset()).isZero();
            }));
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
            var specs = List.of(BreakpointSpec.of("Main.java", 4), BreakpointSpec.of("Helper.java", 4));
            var latest = DebugTraceHelper.traceLatestWithSpecs(compiled, specs, List.of(astA, astB), "");
            assertThat(latest.get(4)).hasSize(2);
            assertThat(latest.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Main.java"));
            assertThat(latest.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Helper.java"));

            try (var session = new cs1302.tracer.internal.execution.TraceSession(cs1302.tracer.api.TraceLimits.unlimited(),
                    cs1302.tracer.api.InspectionPolicy.TRUSTED, false)) {
                assertThat(session.accumulates()).isFalse();
                var latestInSession = DebugTraceHelper.traceLatestWithSpecs(compiled, specs, List.of(astA, astB), "");
                assertThat(latestInSession.get(4)).hasSize(2);
                assertThat(latestInSession.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Main.java"));
                assertThat(latestInSession.get(4)).anySatisfy(s -> assertThat(s.sourcePath()).contains("Helper.java"));
            } // try
        }
    }
}
