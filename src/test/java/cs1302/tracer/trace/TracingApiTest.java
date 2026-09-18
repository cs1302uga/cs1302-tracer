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
            var chronological = DebugTraceHelper.traceChronological(compiled, List.of(5), ast, false, "");
            assertThat(chronological).hasSize(3);
            assertThat(chronological).allSatisfy(snapshot ->
                    assertThat(snapshot.stack().getLast().methodLine()).isEqualTo(5));
        }
    }

    @Test
    void publicLatestCaptureWorksInsideAnExistingSession() throws Exception {
        try (var compiled = CompilationHelper.compile(SOURCE);
                var session = new cs1302.tracer.execution.TraceSession(
                        cs1302.tracer.execution.TraceLimits.unlimited(),
                        cs1302.tracer.execution.InspectionPolicy.TRUSTED, false)) {
            session.phase("trace");
            var snapshots = DebugTraceHelper.traceLatest(compiled, List.of(5),
                    List.of(StaticJavaParser.parse(SOURCE)), "");
            assertThat(snapshots.get(5)).hasSize(1);
            var snapshot = snapshots.get(5).getFirst();
            assertThat(snapshot.getClass().isRecord()).isTrue();
            assertThat(snapshot.getClass().getRecordComponents()).hasSize(8);
            assertThat(snapshot.stdout()).isEqualTo("3\n".getBytes());
            snapshots.get(5).clear();
            assertThat(session.snapshots()).hasSize(1);
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
}
