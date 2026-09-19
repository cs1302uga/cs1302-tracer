package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.StaticJavaParser;
import cs1302.tracer.CompilationHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for PersistentGuestSession verifying session lifecycle,
 * state isolation, stream draining, and failure recovery.
 */
class PersistentGuestSessionTest {

    private static final String HELPER_JOB_SOURCE = """
            public class HelperJob {
                public static void main(String[] args) {
                    helper();
                }
                private static void helper() {
                    try {
                        throw new RuntimeException("caught in student");
                    } catch (RuntimeException e) {
                        int recovered = 42;
                    }
                }
            }
            """;

    private static final String STDERR_JOB_SOURCE = """
            public class StderrJob {
                public static void main(String[] args) {
                    System.err.println("warning message");
                }
            }
            """;

    private static final String LOOP_SOURCE = """
            public class LoopJob {
                public static void main(String[] args) {
                    for (int i = 0; i < 3; i++) {
                        int x = i * 2;
                    }
                }
            }
            """;

    private static final String JOB1_SOURCE = """
            public class JobOne {
                public static void main(String[] args) {
                    int a = 10;
                    int b = 20;
                    int sum = a + b;
                    System.out.println("Sum: " + sum);
                }
            }
            """;

    private static final String JOB2_SOURCE = """
            public class JobTwo {
                public static void main(String[] args) {
                    String msg = "Hello Persistent";
                    System.out.println(msg);
                }
            }
            """;

    private static final String INPUT_JOB_SOURCE = """
            import java.util.Scanner;

            public class InputJob {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    String word = sc.next();
                    int num = sc.nextInt();
                    System.out.println(word + ":" + num);
                }
            }
            """;

    private static final String EXCEPTION_JOB_SOURCE = """
            public class ExceptionJob {
                public static void main(String[] args) {
                    int x = 10;
                    int y = 0;
                    int z = x / y;
                }
            }
            """;

    @Test
    @DisplayName("Executes multiple jobs sequentially reusing a single persistent guest session")
    void sequentialJobsReusingSession() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            assertThat(session.isAlive()).isTrue();
            assertThat(session.completedJobCount()).isEqualTo(0);

            // Job 1 (verifying finalized output slice applies to multiple breakpoint entries)
            var ast1 = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr1 = CompilationHelper.compile(JOB1_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps1 = session.traceWithSpecs(
                        cr1, List.of(BreakpointSpec.of(4), BreakpointSpec.of(6)),
                        List.of(ast1), "", false);
                assertThat(snaps1).containsKey(4);
                assertThat(snaps1).containsKey(6);
                assertThat(snaps1.get(4).getLast().stdoutSlice().asUtf8String())
                        .contains("Sum: 30");
                assertThat(snaps1.get(6).getLast().stdoutSlice().asUtf8String())
                        .contains("Sum: 30");
                ExecutionSnapshot snap1 = snaps1.get(6).getFirst();
                assertThat(snap1.stack().getLast().visibleVariables()).anyMatch(
                        v -> "sum".equals(v.identifier())
                                && v.value() instanceof TraceValue.Primitive.Integer val
                                && val.value() == 30);
            } // try
            assertThat(session.completedJobCount()).isEqualTo(1);
            assertThat(session.isAlive()).isTrue();

            // Job 2 (accumulate = true)
            var ast2 = StaticJavaParser.parse(JOB2_SOURCE);
            try (var cr2 = CompilationHelper.compile(JOB2_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps2 = session.traceWithSpecs(
                        cr2, List.of(BreakpointSpec.of(4)), List.of(ast2), "", true);
                assertThat(snaps2).containsKey(4);
                ExecutionSnapshot snap2 = snaps2.get(4).getFirst();
                assertThat(snap2.stack().getLast().visibleVariables()).anyMatch(
                        v -> "msg".equals(v.identifier())
                                && v.value() instanceof TraceValue.Reference);
            } // try
            assertThat(session.completedJobCount()).isEqualTo(2);

            // Job 3 with input
            var ast3 = StaticJavaParser.parse(INPUT_JOB_SOURCE);
            try (var cr3 = CompilationHelper.compile(INPUT_JOB_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps3 = session.traceWithSpecs(
                        cr3, List.of(BreakpointSpec.of(8), BreakpointSpec.of(-1)),
                        List.of(ast3), "tracer 42\n", false);
                assertThat(snaps3).containsKey(8);
                ExecutionSnapshot snap3 = snaps3.get(8).getFirst();
                assertThat(snap3.stack().getLast().visibleVariables()).anyMatch(
                        v -> "num".equals(v.identifier())
                                && v.value() instanceof TraceValue.Primitive.Integer val
                                && val.value() == 42);
                assertThat(snaps3).containsKey(-1);
                ExecutionSnapshot exitSnap = snaps3.get(-1).getFirst();
                assertThat(exitSnap.stdoutSlice().asUtf8String()).contains("tracer:42");
            } // try
            assertThat(session.completedJobCount()).isEqualTo(3);

            // Job 4 chronological
            var ast4 = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr4 = CompilationHelper.compile(JOB1_SOURCE)) {
                List<ExecutionSnapshot> chrono = session.traceChronologicalWithSpecs(
                        cr4, List.of(BreakpointSpec.of(3), BreakpointSpec.of(4),
                                BreakpointSpec.of(5)),
                        List.of(ast4), "");
                assertThat(chrono).isNotEmpty();

                // Chronological trace through loop covering same top frame suppression
                var astLoop = StaticJavaParser.parse(LOOP_SOURCE);
                try (var crLoop = CompilationHelper.compile(LOOP_SOURCE)) {
                    List<ExecutionSnapshot> loopSnaps = session.traceChronologicalWithSpecs(
                            crLoop, List.of(BreakpointSpec.of(4)), List.of(astLoop), "");
                    assertThat(loopSnaps).hasSize(3);
                } // try
                // Chronological with duplicate line numbers to test suppression
                List<ExecutionSnapshot> dups = session.traceChronologicalWithSpecs(
                        cr4, List.of(BreakpointSpec.of(4), BreakpointSpec.of(4)),
                        List.of(ast4), "");
                assertThat(dups).isNotEmpty();

                // Trace with null specs and null stdin
                Map<Integer, List<ExecutionSnapshot>> nullSpecs = session.traceWithSpecs(
                        cr4, null, List.of(ast4), null, true);
                assertThat(nullSpecs).isNotEmpty();

                // Trace with safeSpecs not containing -1 and snapMainEnd = false
                Map<Integer, List<ExecutionSnapshot>> noExitSpecs = session.traceWithSpecs(
                        cr4, List.of(BreakpointSpec.of(4)), List.of(ast4), "", false);
                assertThat(noExitSpecs).containsKey(4);
            } // try
            assertThat(session.completedJobCount()).isEqualTo(8);
        } // try
    } // sequentialJobsReusingSession

    @Test
    @DisplayName("Handles unhandled guest exceptions and recovers for subsequent runs")
    void handlesExceptionAndRecovers() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var astEx = StaticJavaParser.parse(EXCEPTION_JOB_SOURCE);
            try (var crEx = CompilationHelper.compile(EXCEPTION_JOB_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> noExitSnaps = session.traceWithSpecs(
                        crEx, List.of(BreakpointSpec.of(4)), List.of(astEx), "", false);
                assertThat(noExitSnaps).containsKey(4);
                assertThat(noExitSnaps).containsKey(5);
                assertThat(noExitSnaps).containsKey(-1);

                Map<Integer, List<ExecutionSnapshot>> snaps = session.traceWithSpecs(
                        crEx, List.of(BreakpointSpec.of(4), BreakpointSpec.of(-1)),
                        List.of(astEx), "", false);
                assertThat(snaps).containsKey(4);
                assertThat(snaps).containsKey(5);
                assertThat(snaps).containsKey(-1);
            } // try
            assertThat(session.completedJobCount()).isEqualTo(2);
            assertThat(session.isAlive()).isTrue();

            // Run a clean job right after the exception to ensure warm recovery
            var astClean = StaticJavaParser.parse(JOB2_SOURCE);
            try (var crClean = CompilationHelper.compile(JOB2_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> cleanSnaps = session.traceWithSpecs(
                        crClean, List.of(BreakpointSpec.of(4)), List.of(astClean), "", false);
                assertThat(cleanSnaps).containsKey(4);
            } // try
            assertThat(session.completedJobCount()).isEqualTo(3);
        } // try
    } // handlesExceptionAndRecovers

    private static final String SYSTEM_EXIT_JOB = """
            public class SysExitJob {
                public static void main(String[] args) {
                    System.exit(0);
                }
            }
            """;

    @Test
    @DisplayName("Recovers gracefully after a guest job calls System.exit()")
    void handlesSystemExitAndRecovers() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var astExit = StaticJavaParser.parse(SYSTEM_EXIT_JOB);
            try (var crExit = CompilationHelper.compile(SYSTEM_EXIT_JOB)) {
                session.traceWithSpecs(
                        crExit, List.of(BreakpointSpec.of(3)), List.of(astExit), "", false);
            } // try
            assertThat(session.isAlive()).isFalse();
        } // try
    } // handlesSystemExitAndRecovers

    @Test
    @DisplayName("Output slice update is skipped when session is stopped due to trace_limit")
    void testTraceOutputSuppressedOnTraceLimit() throws Exception {
        cs1302.tracer.execution.TraceLimits limit = new cs1302.tracer.execution.TraceLimits(
                10_000, 10, 10, 10_000, 10_000, 1380, 10_000, 10_000);
        try (PersistentGuestSession session = PersistentGuestSession.create();
                cs1302.tracer.execution.TraceSession ts = new cs1302.tracer.execution.TraceSession(
                        limit, cs1302.tracer.execution.InspectionPolicy.TRUSTED, false)) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps = session.traceWithSpecs(
                        cr, List.of(BreakpointSpec.of(4)), List.of(ast), "", false);
                assertThat(snaps).containsKey(4);
                assertThat(ts.stopReason()).isEqualTo("trace_limit");
                assertThat(snaps.get(4).getLast().stdoutSlice().length()).isEqualTo(0);
            } // try
        } // try

        try (PersistentGuestSession session = PersistentGuestSession.create();
                cs1302.tracer.execution.TraceSession ts = new cs1302.tracer.execution.TraceSession(
                        limit, cs1302.tracer.execution.InspectionPolicy.TRUSTED, false)) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                List<ExecutionSnapshot> chrono = session.traceChronologicalWithSpecs(
                        cr, List.of(BreakpointSpec.of(4)), List.of(ast), "");
                assertThat(chrono).isNotEmpty();
                assertThat(ts.stopReason()).isEqualTo("trace_limit");
                assertThat(chrono.getLast().stdoutSlice().length()).isEqualTo(0);
            } // try
        } // try
    } // testTraceOutputSuppressedOnTraceLimit

    @Test
    @DisplayName("Throws IllegalStateException when attempting to trace on a closed session")
    void closedSessionRejectsTrace() throws Exception {
        PersistentGuestSession session = PersistentGuestSession.create();
        session.close();
        assertThat(session.isAlive()).isFalse();

        var ast = StaticJavaParser.parse(JOB1_SOURCE);
        try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
            assertThatThrownBy(() -> session.traceWithSpecs(
                    cr, List.of(BreakpointSpec.of(6)), List.of(ast), "", false))
                    .isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> session.traceChronologicalWithSpecs(
                    cr, List.of(BreakpointSpec.of(6)), List.of(ast), ""))
                    .isInstanceOf(IllegalStateException.class);
        } // try
    } // closedSessionRejectsTrace

    @Test
    @DisplayName("Executes trace on helper method and caught exception without failing")
    void handlesHelperAndCaughtException() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var astHelper = StaticJavaParser.parse(HELPER_JOB_SOURCE);
            try (var crHelper = CompilationHelper.compile(HELPER_JOB_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps = session.traceWithSpecs(
                        crHelper, List.of(BreakpointSpec.of(9)), List.of(astHelper), "", false);
                assertThat(snaps).containsKey(9);
            } // try
        } // try
    } // handlesHelperAndCaughtException

    @Test
    @DisplayName("handleCleanupFailure correctly rethrows or suppresses exceptions")
    void testHandleCleanupFailure() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            // 1. Primary failure present -> cleanup failure added as suppressed
            Throwable primary = new RuntimeException("primary error");
            Throwable cleanup = new java.io.IOException("cleanup error");
            session.handleCleanupFailure(primary, cleanup);
            assertThat(primary.getSuppressed()).contains(cleanup);

            // 2. InterruptedException
            assertThatThrownBy(() -> session.handleCleanupFailure(
                    null, new InterruptedException("intr")))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).isTrue();

            // 3. RuntimeException
            assertThatThrownBy(() -> session.handleCleanupFailure(
                    null, new IllegalStateException("state")))
                    .isInstanceOf(IllegalStateException.class);

            // 4. Error
            assertThatThrownBy(() -> session.handleCleanupFailure(
                    null, new AssertionError("assert")))
                    .isInstanceOf(AssertionError.class);

            // 5. Generic checked exception wrapped in RuntimeException
            assertThatThrownBy(() -> session.handleCleanupFailure(
                    null, new Exception("generic")))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(Exception.class);
        } // try
    } // testHandleCleanupFailure

    @Test
    @DisplayName("runJobInternal wraps cleanup failure when tracing succeeded")
    void testRunJobInternalCleanupFailure() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                PersistentGuestSession.cleanupHookForTesting = () -> {
                    throw new RuntimeException("simulated cleanup error");
                };
                try {
                    assertThatThrownBy(() -> session.traceWithSpecs(
                            cr, List.of(BreakpointSpec.of(6)), List.of(ast), "", false))
                            .isInstanceOf(RuntimeException.class)
                            .hasMessage("simulated cleanup error");
                    assertThat(session.isAlive()).isFalse();
                } finally {
                    PersistentGuestSession.cleanupHookForTesting = null;
                } // try
            } // try
        } // try
    } // testRunJobInternalCleanupFailure

    @Test
    @DisplayName("cleanupHook runs normally without throwing")
    void testCleanupHookNormalExecution() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                boolean[] hookRan = new boolean[] {false};
                PersistentGuestSession.cleanupHookForTesting = () -> {
                    hookRan[0] = true;
                };
                try {
                    session.traceWithSpecs(
                            cr, List.of(BreakpointSpec.of(6)), List.of(ast), "", false);
                    assertThat(hookRan[0]).isTrue();
                } finally {
                    PersistentGuestSession.cleanupHookForTesting = null;
                } // try
            } // try
        } // try
    } // testCleanupHookNormalExecution

    @Test
    @DisplayName("runJobInternal suppresses cleanup failure when primary job failed")
    void testRunJobInternalBothFail() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                PersistentGuestSession.cleanupHookForTesting = () -> {
                    throw new RuntimeException("simulated cleanup error");
                };
                try {
                    assertThatThrownBy(() -> {
                        session.runJobInternal(cr, List.of(), List.of(ast), "", false,
                                (line, snap) -> {
                                    throw new RuntimeException("primary job error");
                                });
                    })
                            .isInstanceOf(RuntimeException.class)
                            .hasMessage("primary job error");
                    assertThat(session.isAlive()).isFalse();
                } finally {
                    PersistentGuestSession.cleanupHookForTesting = null;
                } // try
            } // try
        } // try
    } // testRunJobInternalBothFail

    @Test
    @DisplayName("traceWithSpecs with no hits returns empty map without error")
    void testTraceWithSpecsNoHits() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps = session.traceWithSpecs(
                        cr, List.of(BreakpointSpec.of(999)), List.of(ast), "", false);
                assertThat(snaps).isEmpty();
            } // try
        } // try
    } // testTraceWithSpecsNoHits

    @Test
    @DisplayName("Handles stderr output without failing")
    void handlesStderrOutput() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(STDERR_JOB_SOURCE);
            try (var cr = CompilationHelper.compile(STDERR_JOB_SOURCE)) {
                Map<Integer, List<ExecutionSnapshot>> snaps = session.traceWithSpecs(
                        cr, List.of(BreakpointSpec.of(3), BreakpointSpec.of(-1)),
                        List.of(ast), "", false);
                assertThat(snaps).containsKey(-1);
                ExecutionSnapshot exitSnap = snaps.get(-1).getFirst();
                assertThat(exitSnap.stderrSlice().asUtf8String()).contains("warning message");

                List<ExecutionSnapshot> chrono = session.traceChronologicalWithSpecs(
                        cr, List.of(BreakpointSpec.of(3)), List.of(ast), "");
                assertThat(chrono).isNotEmpty();
                assertThat(chrono.getLast().stderrSlice().asUtf8String()).contains("warning message");
            } // try
        } // try
    } // handlesStderrOutput

    @Test
    @DisplayName("traceChronologicalWithSpecs with no hits returns empty list without error")
    void testTraceChronologicalWithSpecsNoHits() throws Exception {
        try (PersistentGuestSession session = PersistentGuestSession.create()) {
            var ast = StaticJavaParser.parse(JOB1_SOURCE);
            try (var cr = CompilationHelper.compile(JOB1_SOURCE)) {
                List<ExecutionSnapshot> chrono = session.traceChronologicalWithSpecs(
                        cr, List.of(BreakpointSpec.of(999)), List.of(ast), "");
                assertThat(chrono).isEmpty();
            } // try
        } // try
    } // testTraceChronologicalWithSpecsNoHits
} // PersistentGuestSessionTest
