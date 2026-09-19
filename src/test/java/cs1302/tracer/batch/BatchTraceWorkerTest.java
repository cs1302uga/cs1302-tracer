package cs1302.tracer.batch;

import cs1302.tracer.trace.PersistentGuestSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Worker executes chronological trace with empty breakpoints defaulting to all lines")
    void testWorkerDefaultChronological() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-3", BASIC_SOURCE, "pytutor", null, List.of(),
                    true, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-3");
            assertThat(resp.result().complete()).isTrue();
        } // try
    } // testWorkerDefaultChronological

    @Test
    @DisplayName("Worker rejects empty source code")
    void testWorkerRejectsEmptySource() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-empty", "   ", "pytutor", null, List.of("1"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-empty");
            assertThat(resp.result().complete()).isFalse();
            assertThat(resp.result().status()).isEqualTo("failed");
        } // try
    } // testWorkerRejectsEmptySource

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
    @DisplayName("Worker rejects null source code")
    void testWorkerRejectsNullSource() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-null", null, "pytutor", null, List.of("1"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp = worker.execute(req);
            assertThat(resp.id()).isEqualTo("job-null");
            assertThat(resp.result().complete()).isFalse();
        } // try
    } // testWorkerRejectsNullSource

    @Test
    @DisplayName("Worker executes line-specific breakpoints with and without accumulate")
    void testWorkerLineSpecificBreakpoints() {
        BatchTraceWorker worker = new BatchTraceWorker(5);
        try {
            // accBps = true
            BatchJobRequest reqAcc = new BatchJobRequest(
                    "job-acc", BASIC_SOURCE, "pytutor", "test stdin", List.of("5"),
                    false, true, false, false, false, "fqn", null, null);
            BatchJobResponse respAcc = worker.execute(reqAcc);
            assertThat(respAcc.result().complete()).isTrue();

            // accBps = false
            BatchJobRequest reqNoAcc = new BatchJobRequest(
                    "job-no-acc", BASIC_SOURCE, "modern", "test stdin", List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse respNoAcc = worker.execute(reqNoAcc);
            assertThat(respNoAcc.result().complete()).isTrue();
        } finally {
            worker.close();
            worker.close();
        } // try
    } // testWorkerLineSpecificBreakpoints

    @Test
    @DisplayName("Worker handles packaged source and explicit inspection policy")
    void testWorkerPackagedAndInspection() {
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-pkg", PACKAGE_SOURCE, "pytutor", null, List.of("4"),
                    false, false, false, false, false, "fqn", null, cs1302.tracer.execution.InspectionPolicy.FIELDS);
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
                    false, false, false, false, false, "fqn", null, cs1302.tracer.execution.InspectionPolicy.TRUSTED);
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
    @DisplayName("Worker recycles session when completedJobCount reaches maxJobsPerWorker")
    void testWorkerRecyclesSessionAtLimit() {
        try (BatchTraceWorker worker = new BatchTraceWorker(1)) {
            BatchJobRequest req = new BatchJobRequest(
                    "job-lim-1", BASIC_SOURCE, "pytutor", null, List.of("5"),
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse resp1 = worker.execute(req);
                        assertThat(resp1.result().complete()).isTrue();

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
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() {}
            @Override public Process destroyForcibly() { return this; }
            @Override public boolean isAlive() { throw new RuntimeException("Simulated VM crash"); }
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

        BatchJobRequest req = new BatchJobRequest(
                "job-fail", BASIC_SOURCE, "pytutor", null, List.of("5"),
                false, false, false, false, false, "fqn", null, null);
        BatchJobResponse resp = worker.execute(req);
        assertThat(resp.result().status()).isEqualTo("failed");
        assertThat(resp.result().phase()).isEqualTo("tracer");
        assertThat(resp.result().stopReason()).isEqualTo("tracer_error");
        assertThat(resp.result().diagnostics()).anyMatch(d -> d.contains("Simulated VM crash"));
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

            BatchJobRequest badTsReq = new BatchJobRequest(
                    "bad-ts", BASIC_SOURCE, "modern", null, List.of("5"),
                    false, false, false, false, false, "invalid_ts", null, null);
            BatchJobResponse resp2 = worker.execute(badTsReq);
            assertThat(resp2.result().status()).isEqualTo("failed");
            assertThat(resp2.result().phase()).isEqualTo("validation");
            assertThat(resp2.result().format()).isEqualTo("modern");
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
            assertThat(respMod.result().complete()).isTrue();

            BatchJobRequest reqPy = new BatchJobRequest(
                    "exit-single-py", SYSTEM_EXIT_SOURCE, "pytutor", null, null,
                    false, false, false, false, false, "fqn", null, null);
            BatchJobResponse respPy = worker.execute(reqPy);
            assertThat(respPy.id()).isEqualTo("exit-single-py");
            assertThat(respPy.result().complete()).isTrue();
        } // try
    } // testWorkerSingleSnapshotEarlyExit
}
