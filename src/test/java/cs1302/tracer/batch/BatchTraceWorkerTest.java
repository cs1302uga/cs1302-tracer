package cs1302.tracer.batch;

import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.PersistentGuestSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for BatchTraceWorker lifecycle and execution.
 */
class BatchTraceWorkerTest {

    private static final String MULTI_FILE_SOURCE = """
            // --- A.java ---
            public class A {
                public static int foo() {
                    int x = 1;
                    return x;
                }
            }
            // --- B.java ---
            public class B {
                public static void main(String[] args) {
                    int y = A.foo();
                    System.out.println(y);
                }
            }
            """;

    private static final String PACKAGE_SOURCE = """
            package com.example;
            public class Packaged {
                public static void main(String[] args) {
                    int val = 100;
                }
            }
            """;

    private static final String EXISTING_PACKAGE_SOURCE = """
            package cs1302.tracer;
            public class DummyPackaged {
                public static void main(String[] args) {
                    int x = 42;
                }
            }
            """;

    private static final String SYSTEM_EXIT_SOURCE = """
            public class ExitProg {
                public static void main(String[] args) {
                    System.exit(0);
                }
            }
            """;

    private static final String LOOP_SOURCE = """
            public class LoopProg {
                public static void main(String[] args) {
                    for (int i = 0; i < 2; i++) {
                        int x = 42;
                    }
                }
            }
            """;

    private static final String BASIC_SOURCE = """
            public class BasicBatch {
                public static void main(String[] args) {
                    int val = 42;
                    String text = "test";
                    System.out.println(text + ":" + val);
                }
            }
            """;

    private static final String EXCEPTION_SOURCE = """
            public class BadBatch {
                public static void main(String[] args) {
                    int a = 1 / 0;
                }
            }
            """;

    private static final String INSTANCE_MAIN_WITHOUT_NOARG_CTOR = """
            public class NeedsCtorArg {
                private final int value;
                public NeedsCtorArg(int value) {
                    this.value = value;
                }
                void main() {
                    System.out.println(value);
                }
            }
            """;

    private static final String INVALID_SYNTAX = """
            public class BadSyntax {
                invalid syntax here
            }
            """;

    @Test
    @DisplayName("Worker executes valid job producing PyTutor trace")
    void testWorkerExecutesPyTutor() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-1", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-1");
            assertThat(resp.result().complete()).isTrue();
            assertThat(resp.result().status()).isEqualTo("completed");
            assertThat(resp.result().trace()).isNotNull();
        } // try
    } // testWorkerExecutesPyTutor

    @Test
    @DisplayName("Worker executes valid job with Modern format, all breakpoints, and flags")
    void testWorkerExecutesModernAllBreakpoints() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-2", BASIC_SOURCE, "modern", null, List.of("4", "5"),
                    true, false, true, true, true, "simple", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-2");
            assertThat(resp.result().complete()).isTrue();
            assertThat(resp.result().status()).isEqualTo("completed");
        } // try
    } // testWorkerExecutesModernAllBreakpoints

    @Test
    @DisplayName("Worker preserves completed snapshots when a snapshot limit stops tracing")
    void testWorkerPreservesPartialTraceAtSnapshotLimit() {
        TraceLimits limits = new TraceLimits(0, 1, 0, 0, 0, 0, 0, 0);
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-partial", LOOP_SOURCE, "modern", null, List.of("4", "5"),
                    true, false, false, false, false, "fqn", limits, null);
            BatchJobResponse resp = worker.execute(req);

            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().stopReason()).isEqualTo("snapshot_limit");
            assertThat(resp.result().trace()).isNotNull();
            assertThat(resp.result().counters().get("snapshotsRetained")).isEqualTo(1L);
        } // try
    } // testWorkerPreservesPartialTraceAtSnapshotLimit

    @ParameterizedTest
    @ValueSource(strings = {"modern", "pytutor"})
    void targetedEnvelopesReduceInCaptureOrderOnSuccessAndFailure(String format) {
        String root = format.equals("modern") ? "steps" : "trace";
        String source = """
                public class RepeatedCalls {
                    static void visit(int i) {
                        int x = i;
                    }
                    public static void main(String[] args) {
                        for (int i = 0; i < 3; i++) {
                            visit(i);
                        }
                    }
                }
                """;
        try (BatchTraceWorker worker = new BatchTraceWorker(20)) {
            for (boolean accumulate : List.of(false, true)) {
                for (boolean limited : List.of(false, true)) {
                    TraceLimits limits = new TraceLimits(0, limited ? 4 : 0, 0, 0, 0, 0, 0, 0);
                    BatchJobResponse response = worker.execute(new BatchJobRequest(
                            "targeted", source, format, null, List.of("3", "7"),
                            false, accumulate, false, false, false, "fqn", limits, null));
                    assertThat(response.result().complete()).isEqualTo(!limited);
                    assertThat(response.result().stopReason())
                            .isEqualTo(limited ? "snapshot_limit" : null);
                    var payload = PyTutorSerializer.getGson(false)
                            .toJsonTree(response.result().trace()).getAsJsonObject();
                    assertThat(payload.has(root)).isTrue();
                    List<Integer> lines = new java.util.ArrayList<>();
                    payload.getAsJsonArray(root).forEach(
                            step -> lines.add(step.getAsJsonObject().get("line").getAsInt()));
                    assertThat(lines).containsExactlyElementsOf(accumulate
                            ? (limited ? List.of(7, 3, 7, 3) : List.of(7, 3, 7, 3, 7, 3))
                            : List.of(7, 3));
                    if (!limited) {
                        assertThat(response.result().phase()).isEqualTo("serialize");
                    } // if
                } // for
            } // for
        } // try
    } // targetedEnvelopesReduceInCaptureOrderOnSuccessAndFailure

    @ParameterizedTest
    @ValueSource(strings = {"modern", "pytutor"})
    void everyBatchModeUsesASequenceRoot(String format) {
        String root = format.equals("modern") ? "steps" : "trace";
        try (BatchTraceWorker worker = new BatchTraceWorker(10)) {
            for (boolean chronological : List.of(false, true)) {
                for (List<String> breakpoints : java.util.Arrays.asList(null, List.of("4", "5"))) {
                    BatchJobResponse response = worker.execute(new BatchJobRequest(
                            "schema", BASIC_SOURCE, format, null, breakpoints,
                            chronological, false, false, false, false, "fqn", null, null));
                    assertThat(response.result().complete()).isTrue();
                    assertThat(response.result().phase()).isEqualTo("serialize");
                    var payload = PyTutorSerializer.getGson(false)
                            .toJsonTree(response.result().trace()).getAsJsonObject();
                    assertThat(payload.has(root)).isTrue();
                    assertThat(payload.getAsJsonArray(root).isEmpty()).isFalse();
                    if (!chronological) {
                        payload.getAsJsonArray(root).forEach(step -> assertThat(
                                step.getAsJsonObject().get("stdout").getAsString())
                                .isEqualTo("test:42\n"));
                    } // if
                } // for
            } // for
        } // try
    } // everyBatchModeUsesASequenceRoot

    @Test
    void closingGuestPrintStreamsDoesNotLoseLaterOutput() {
        String source = """
                public class CloseStreams {
                    public static void main(String[] args) {
                        System.out.print("before");
                        System.err.print("before");
                        System.out.close();
                        System.err.close();
                        System.out.print("after");
                        System.err.print("after");
                    }
                }
                """;
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            for (int job = 0; job < 2; job++) {
                BatchJobResponse response = worker.execute(new BatchJobRequest(
                        "close-" + job, source, "modern", null, null,
                        false, false, false, false, false, "fqn", null, null));
                assertThat(response.result().complete()).isTrue();
                assertThat(response.result().stdout()).isEqualTo("beforeafter");
                assertThat(response.result().stderr()).isEqualTo("beforeafter");
            } // for
        } // try
    } // closingGuestPrintStreamsDoesNotLoseLaterOutput

    @Test
    @DisplayName("Worker executes job with default chronological mode when breakpoints empty")
    void testWorkerDefaultChronological() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-3", BASIC_SOURCE, "pytutor", null, List.of(),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-3");
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerDefaultChronological

    @Test
    @DisplayName("Worker rejects empty source code")
    void testWorkerRejectsEmptySource() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            TraceLimits customLimits = TraceLimits.instructorDefaults();
            BatchJobRequest req = new BatchJobRequest(
                    "job-empty", "   ", "pytutor", null, List.of("1"),
                    false, false, false, false, false, "fqn", customLimits, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-empty");
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().status()).isEqualTo("failed");
            assertThat(resp.result().phase()).isEqualTo("source");
            assertThat(resp.result().limits()).isEqualTo(customLimits);
        } // try
    } // testWorkerRejectsEmptySource

    @Test
    @DisplayName("Worker rejects null source code")
    void testWorkerRejectsNullSource() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            TraceLimits customLimits = TraceLimits.instructorDefaults();
            BatchJobRequest req = new BatchJobRequest(
                    "job-null", null, "pytutor", null, List.of("1"),
                    false, false, false, false, false, "fqn", customLimits, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-null");
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().status()).isEqualTo("failed");
            assertThat(resp.result().phase()).isEqualTo("source");
            assertThat(resp.result().limits()).isEqualTo(customLimits);
        } // try
    } // testWorkerRejectsNullSource

    @Test
    @DisplayName("Worker handles compilation failure gracefully")
    void testWorkerCompilationFailure() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-bad", INVALID_SYNTAX, "pytutor", null, List.of("1"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-bad");
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().status()).isEqualTo("failed");
        } // try
    } // testWorkerCompilationFailure

    @Test
    @DisplayName("Worker recycles session when maxJobsPerWorker threshold is reached")
    void testWorkerRecycling() {
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            BatchJobRequest req1 = new BatchJobRequest(
                    "job-rec-1", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp1 = worker.execute(req1);
            assertThat(resp1.result().complete()).isTrue();

            BatchJobRequest req2 = new BatchJobRequest(
                    "job-rec-2", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp2 = worker.execute(req2);
            assertThat(resp2.result().complete()).isTrue();
        } // try
    } // testWorkerRecycling

    @Test
    @DisplayName("Worker handles guest program throwing unhandled exception")
    void testWorkerHandlesGuestException() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-ex", EXCEPTION_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-ex");
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().status()).isEqualTo("failed");
            assertThat(resp.result().stopReason()).isEqualTo("guest_exception");
        } // try
    } // testWorkerHandlesGuestException

    @Test
    @DisplayName("Worker handles line-specific targeted breakpoints")
    void testWorkerLineSpecificBreakpoints() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-lines", BASIC_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-lines");
            assertThat(resp.result().complete()).isTrue();
            assertThat(resp.result().status()).isEqualTo("completed");

        } // try
    } // testWorkerLineSpecificBreakpoints

    @Test
    @DisplayName("Worker handles packaged source and explicit inspection policy")
    void testWorkerPackagedAndInspection() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-pkg", PACKAGE_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null,
                    cs1302.tracer.execution.InspectionPolicy.FIELDS);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerPackagedAndInspection


    @Test
    @DisplayName("Worker handles explicit TRUSTED inspection policy")
    void testWorkerTrustedInspection() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-trusted", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null,
                    cs1302.tracer.execution.InspectionPolicy.TRUSTED);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerTrustedInspection

    @Test
    @DisplayName("Worker handles case where no snapshots are captured without throwing NoSuchElementException")
    void testWorkerEmptySnapshots() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-empty-snap", BASIC_SOURCE, "pytutor", null, List.of("999"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-empty-snap");
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerEmptySnapshots



    @Test
    @DisplayName("Worker handles packaged source with default TRUSTED inspection policy")
    void testWorkerPackagedTrusted() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-pkg-trusted", EXISTING_PACKAGE_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerPackagedTrusted

    @Test
    @DisplayName("Worker handles early exit with empty snapshots taking false branch of reconciliation")
    void testWorkerSystemExitEmptySnapshots() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-sys-exit", SYSTEM_EXIT_SOURCE, "pytutor", null, List.of("999"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-sys-exit");
        } // try
    } // testWorkerSystemExitEmptySnapshots

    @Test
    @DisplayName("Worker recovers when previous session was closed or killed")
    void testWorkerRecoversFromKilledSession() throws Exception {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req1 = new BatchJobRequest(
                    "job-alive-1", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp1 = worker.execute(req1);
            assertThat(resp1.result().complete()).isTrue();

            var field = BatchTraceWorker.class.getDeclaredField("session");
            field.setAccessible(true);
            PersistentGuestSession sess = (PersistentGuestSession) field.get(worker);
            sess.close();

            BatchJobResponse resp2 = worker.execute(req1);
            assertThat(resp2.result().complete()).isTrue();
        } // try
    } // testWorkerRecoversFromKilledSession

    @Test
    @DisplayName("Worker handles InterruptedException during execution")
    void testWorkerInterrupted() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-int", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            Thread.currentThread().interrupt();
            BatchJobResponse resp = worker.execute(req);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(resp.result().complete()).isFalse();
        } // try
    } // testWorkerInterrupted

    @Test
    @DisplayName("restoreInterruptIfInterrupted correctly restores interrupt for interruption exceptions")
    void testRestoreInterruptIfInterrupted() {
        BatchTraceWorker.restoreInterruptIfInterrupted(new InterruptedException());
        assertThat(Thread.interrupted()).isTrue();

        BatchTraceWorker.restoreInterruptIfInterrupted(new java.io.InterruptedIOException());
        assertThat(Thread.interrupted()).isTrue();

        BatchTraceWorker.restoreInterruptIfInterrupted(new RuntimeException());
        assertThat(Thread.interrupted()).isFalse();
    } // testRestoreInterruptIfInterrupted

    @Test
    @DisplayName("Worker recycles session when completedJobCount equals maxJobsPerWorker")
    void testWorkerRecyclesSessionAtLimit() throws Exception {
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-recycle-limit", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp1 = worker.execute(req);
            assertThat(resp1.result().complete()).isTrue();

            // Next execution will recycle because completedJobCount >= maxJobsPerWorker (1 >= 1)
            BatchJobResponse resp2 = worker.execute(req);
            assertThat(resp2.result().complete()).isTrue();
        } // try
    } // testWorkerRecyclesSessionAtLimit

    @Test
    @DisplayName("Worker handles launch failure in ensureSession")
    void testWorkerLaunchFailure() throws Exception {
        BatchTraceWorker worker = new BatchTraceWorker(5);
        java.lang.reflect.Field field = BatchTraceWorker.class.getDeclaredField("session");
        field.setAccessible(true);
        Process proc = new Process() {
            @Override public java.io.OutputStream getOutputStream() {
                return java.io.OutputStream.nullOutputStream();
            } // getOutputStream
            @Override public java.io.InputStream getInputStream() {
                return java.io.InputStream.nullInputStream();
            } // getInputStream
            @Override public java.io.InputStream getErrorStream() {
                return java.io.InputStream.nullInputStream();
            } // getErrorStream
            @Override public int waitFor() {
                return 0;
            } // waitFor
            @Override public int exitValue() {
                return 0;
            } // exitValue
            @Override public void destroy() {}
            @Override public Process destroyForcibly() {
                return this;
            } // destroyForcibly
            @Override public boolean isAlive() {
                throw new RuntimeException("Simulated VM crash");
            } // isAlive
        };
        var vm = (com.sun.jdi.VirtualMachine) java.lang.reflect.Proxy.newProxyInstance(
                com.sun.jdi.VirtualMachine.class.getClassLoader(),
                new Class<?>[] {com.sun.jdi.VirtualMachine.class},
                (self, m, args) -> {
                    if ("process".equals(m.getName())) {
                        return proc;
                    } // if
                    return null;
                });
        var harnessType = (com.sun.jdi.ClassType) java.lang.reflect.Proxy.newProxyInstance(
                com.sun.jdi.ClassType.class.getClassLoader(),
                new Class<?>[] {com.sun.jdi.ClassType.class},
                (self, m, args) -> null);
        var constructor = PersistentGuestSession.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var session = (PersistentGuestSession) constructor.newInstance(
                vm, null, null, harnessType, null, null);
        field.set(worker, session);

        TraceLimits customLimits = TraceLimits.instructorDefaults();
        BatchJobRequest req = new BatchJobRequest(
                "job-fail", BASIC_SOURCE, "pytutor", null, List.of("5"),
                false, false, false, false, false, "fqn", customLimits, null);
        BatchJobResponse resp = worker.execute(req);
        assertThat(resp.result().status()).isEqualTo("failed");
        assertThat(resp.result().phase()).isEqualTo("tracer");
        assertThat(resp.result().stopReason()).isEqualTo("tracer_error");
        assertThat(resp.result().diagnostics()).anyMatch(d -> d.contains("Simulated VM crash"));
        assertThat(resp.result().limits()).isEqualTo(customLimits);
    } // testWorkerLaunchFailure

    @Test
    @DisplayName("execute rejects null request")
    void testExecuteNullRequest() {
        try (var worker = new BatchTraceWorker(10)) {
            assertThatThrownBy(() -> worker.execute(null))
                    .isInstanceOf(IllegalArgumentException.class);
        } // try
    } // testExecuteNullRequest

    @Test
    @DisplayName("Worker returns validation failure response for invalid format or typeStyle")
    void testWorkerValidationFailure() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest badFmtReq = new BatchJobRequest(
                    "bad-fmt", BASIC_SOURCE, "invalid_fmt", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp1 = worker.execute(badFmtReq);
            assertThat(resp1.result().status()).isEqualTo("failed");
            assertThat(resp1.result().phase()).isEqualTo("validation");
            assertThat(resp1.result().limits()).isEqualTo(TraceLimits.unlimited());

            BatchJobRequest badTsReq = new BatchJobRequest(
                    "bad-ts", BASIC_SOURCE, "modern", null, List.of("5"),
                    false, false, false, false, false, "invalid_ts", null, null);
            BatchJobResponse resp2 = worker.execute(badTsReq);
            assertThat(resp2.result().status()).isEqualTo("failed");
            assertThat(resp2.result().phase()).isEqualTo("validation");
            assertThat(resp2.result().format()).isEqualTo("modern");
            assertThat(resp2.result().limits()).isEqualTo(TraceLimits.unlimited());
        } // try
    } // testWorkerValidationFailure

    @Test
    @DisplayName("Worker executes targeted breakpoints with multiple hits for PyTutor and Modern")
    void testWorkerTargetedLoopMultipleHits() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            // Modern, accBps = false, loop hit > 1
            BatchJobRequest reqModNoAcc = new BatchJobRequest(
                    "mod-loop-noacc", LOOP_SOURCE, "modern", "in", List.of("4"),
                    false, false, false, false, false, "simple", null, null);
            BatchJobResponse respModNoAcc = worker.execute(reqModNoAcc);
            assertThat(respModNoAcc.result().complete()).isTrue();

            // Modern, accBps = true, loop hit > 1
            BatchJobRequest reqModAcc = new BatchJobRequest(
                    "mod-loop-acc", LOOP_SOURCE, "modern", "in", List.of("4"),
                    false, true, false, false, false, "simple", null, null);
            BatchJobResponse respModAcc = worker.execute(reqModAcc);
            assertThat(respModAcc.result().complete()).isTrue();

            // PyTutor, accBps = false, loop hit > 1
            BatchJobRequest reqPyNoAcc = new BatchJobRequest(
                    "py-loop-noacc", LOOP_SOURCE, "pytutor", "in", List.of("4"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse respPyNoAcc = worker.execute(reqPyNoAcc);
            assertThat(respPyNoAcc.result().complete()).isTrue();

            // PyTutor, accBps = true, loop hit > 1
            BatchJobRequest reqPyAcc = new BatchJobRequest(
                    "py-loop-acc", LOOP_SOURCE, "pytutor", "in", List.of("4"),
                    false, true, false, false, false, "fqn", null, null);
            BatchJobResponse respPyAcc = worker.execute(reqPyAcc);
            assertThat(respPyAcc.result().complete()).isTrue();
        } // try
    } // testWorkerTargetedLoopMultipleHits

    @Test
    @DisplayName("Worker executes chronological trace with non-empty breakpoints, modern format, and stdin")
    void testWorkerChronologicalWithNonEmptySpecs() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-chrono-specs", BASIC_SOURCE, "modern", "test-stdin", List.of("4", "5"),
                    true, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-chrono-specs");
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerChronologicalWithNonEmptySpecs

    @Test
    @DisplayName("Worker executes single snapshot trace when breakpoints is null for PyTutor")
    void testWorkerSingleSnapshotTracePyTutor() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-single-py", BASIC_SOURCE, "pytutor", null, null,
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-single-py");
            assertThat(resp.result().complete()).isTrue();
            assertThat(resp.result().trace()).isNotNull();
        } // try
    } // testWorkerSingleSnapshotTracePyTutor

    @Test
    @DisplayName("Worker executes single snapshot trace when breakpoints is null for Modern")
    void testWorkerSingleSnapshotTraceModern() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-single-mod", BASIC_SOURCE, "modern", "sample-input", null,
                    false, false, false, false, false, "simple", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-single-mod");
            assertThat(resp.result().complete()).isTrue();
            assertThat(resp.result().trace()).isNotNull();
        } // try
    } // testWorkerSingleSnapshotTraceModern

    @Test
    @DisplayName("Worker executes chronological trace with null breakpoints defaulting to all lines")
    void testWorkerChronologicalNullBreakpoints() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-chrono-null-bp", BASIC_SOURCE, "pytutor", null, null,
                    true, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-chrono-null-bp");
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerChronologicalNullBreakpoints

    @Test
    @DisplayName("Worker preserves snapshot list when multiple files hit the same line")
    void testWorkerMultiFileSameLinePreserved() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            // Modern format
            BatchJobRequest reqMod = new BatchJobRequest(
                    "mf-mod", MULTI_FILE_SOURCE, "modern", null, List.of("4"),
                    false, false, false, false, false, "simple", null, null);
            BatchJobResponse respMod = worker.execute(reqMod);
            assertThat(respMod.result().complete()).isTrue();

            // PyTutor format
            BatchJobRequest reqPy = new BatchJobRequest(
                    "mf-py", MULTI_FILE_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse respPy = worker.execute(reqPy);
            assertThat(respPy.result().complete()).isTrue();
        } // try
    } // testWorkerMultiFileSameLinePreserved

    @Test
    @DisplayName("Worker executes single snapshot trace when breakpoints is null and guest exits early")
    void testWorkerSingleSnapshotEarlyExit() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest reqMod = new BatchJobRequest(
                    "exit-single-mod", SYSTEM_EXIT_SOURCE, "modern", null, null,
                    false, false, false, false, false, "simple", null, null);
            BatchJobResponse respMod = worker.execute(reqMod);
            assertThat(respMod.id()).isEqualTo("exit-single-mod");
            assertThat(respMod.result().complete()).isFalse();
            assertThat(respMod.result().stopReason()).isEqualTo("guest_exit");

            BatchJobRequest reqPy = new BatchJobRequest(
                    "exit-single-py", SYSTEM_EXIT_SOURCE, "pytutor", null, null,
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse respPy = worker.execute(reqPy);
            assertThat(respPy.id()).isEqualTo("exit-single-py");
            assertThat(respPy.result().complete()).isFalse();
            assertThat(respPy.result().stopReason()).isEqualTo("guest_exit");
        } // try
    } // testWorkerSingleSnapshotEarlyExit

    @Test
    @DisplayName("Worker reports harness launch failures instead of empty success")
    void testWorkerReportsHarnessLaunchFailure() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-harness-failure", INSTANCE_MAIN_WITHOUT_NOARG_CTOR, "modern",
                    null, null, false, false, false, false, false, "simple", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().stopReason()).isEqualTo("tracer_error");
            assertThat(resp.result().diagnostics()).anyMatch(
                    d -> d.contains("NoSuchMethodException"));
        } // try
    } // testWorkerReportsHarnessLaunchFailure
    @Test
    void startupDeathIsReportedAndAReplacementCanRun() throws Exception {
        var dead = PersistentGuestSession.create();
        dead.close();
        var launches = new java.util.concurrent.atomic.AtomicInteger();
        try (var worker = new BatchTraceWorker(5, () -> launches.getAndIncrement() == 0
                ? dead : PersistentGuestSession.create())) {
            var request = request(BASIC_SOURCE, false);
            var failed = worker.execute(request).result();
            assertThat(failed.status()).isEqualTo("failed");
            assertThat(failed.diagnostics()).anyMatch(d -> d.contains("terminated during startup"));
            assertThat(worker.execute(request).result().complete()).isTrue();
            assertThat(launches.get()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void guestDeathPreservesAnExistingStopReason(boolean alreadyStopped) throws Exception {
        var hook = PersistentGuestSession.class.getDeclaredField("cleanupHookForTesting");
        hook.setAccessible(true);
        var alive = PersistentGuestSession.class.getDeclaredField("alive");
        alive.setAccessible(true);
        try (var guest = PersistentGuestSession.create();
                var worker = new BatchTraceWorker(5, () -> guest)) {
            hook.set(null, (Runnable) () -> {
                try {
                    if (alreadyStopped) {
                        cs1302.tracer.execution.TraceSession.current().stop("snapshot_limit");
                    }
                    alive.setBoolean(guest, false);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
            var result = worker.execute(request(BASIC_SOURCE, false)).result();
            assertThat(result.stopReason()).isEqualTo(alreadyStopped ? "snapshot_limit" : "guest_exit");
            assertThat(result.complete()).isFalse();
        } finally {
            hook.set(null, null);
        }
    }

    @Test
    void partialSerializationFailurePreservesOriginalFailure() throws Exception {
        var serialize = BatchTraceWorker.class.getDeclaredMethod("serializePartialPayload",
                BatchJobRequest.class, cs1302.tracer.execution.TraceSession.class,
                cs1302.tracer.model.TraceFormat.class, cs1302.tracer.model.TypeStyle.class,
                Throwable.class);
        serialize.setAccessible(true);
        try (var guest = PersistentGuestSession.create();
                var worker = new BatchTraceWorker(5);
                var session = new cs1302.tracer.execution.TraceSession(
                        TraceLimits.unlimited(), cs1302.tracer.execution.InspectionPolicy.FIELDS, true)) {
            session.attach(guest.process(), false);
            session.commit(emptySnapshot());
            var original = new IllegalStateException("trace failed");
            assertThat(serialize.invoke(worker, request("invalid Java", false), session,
                    cs1302.tracer.model.TraceFormat.MODERN, cs1302.tracer.model.TypeStyle.FQN,
                    original)).isNull();
            assertThat(original.getSuppressed()).hasSize(1);
            assertThat(original.getSuppressed()[0]).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void retainedSnapshotsRefreshOnlyTheLastHitPerLocation() throws Exception {
        var retain = BatchTraceWorker.class.getDeclaredMethod("retainedSnapshots",
                BatchJobRequest.class, cs1302.tracer.execution.TraceSession.class);
        retain.setAccessible(true);
        try (var worker = new BatchTraceWorker(5);
                var session = new cs1302.tracer.execution.TraceSession(
                        TraceLimits.unlimited(), cs1302.tracer.execution.InspectionPolicy.FIELDS, true)) {
            var first = emptySnapshot();
            var last = new cs1302.tracer.trace.ExecutionSnapshot(
                    List.of(), List.of(), java.util.Map.of(), new byte[] {65}, new byte[0]);
            session.commit(first);
            session.commit(last);
            assertThat(retain.invoke(worker, request(BASIC_SOURCE, true), session))
                    .isEqualTo(List.of(first, last));
            var result = (List<?>) retain.invoke(worker, request(BASIC_SOURCE, false), session);
            assertThat(result.getFirst()).isSameAs(first);
            assertThat(((cs1302.tracer.trace.ExecutionSnapshot) result.getLast()).stdout())
                    .containsExactly((byte) 65);
            session.stop("trace_limit");
            var limited = (List<?>) retain.invoke(worker, request(BASIC_SOURCE, false), session);
            assertThat(limited.getLast()).isSameAs(last);
        }
    }

    private static BatchJobRequest request(String source, boolean allBreakpoints) {
        return new BatchJobRequest("coverage-job", source, "modern", null, null,
                allBreakpoints, false, false, false, false, "fqn", TraceLimits.unlimited(), null);
    }

    private static cs1302.tracer.trace.ExecutionSnapshot emptySnapshot() {
        return new cs1302.tracer.trace.ExecutionSnapshot(
                List.of(), List.of(), java.util.Map.of(), new byte[0], new byte[0]);
    }

} // BatchTraceWorkerTest
