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
    @DisplayName("canReconcileSnapshots returns true only when both lists are non-empty")
    void testCanReconcileSnapshots() {
        cs1302.tracer.trace.ExecutionSnapshot dummy =
                new cs1302.tracer.trace.ExecutionSnapshot(
                        List.of(), List.of(), java.util.Map.of(),
                        cs1302.tracer.trace.OutputSlice.empty(),
                        cs1302.tracer.trace.OutputSlice.empty(),
                        java.util.Optional.empty(), "", 0);

        assertThat(BatchTraceWorker.canReconcileSnapshots(List.of(), List.of())).isFalse();
        assertThat(BatchTraceWorker.canReconcileSnapshots(List.of(dummy), List.of())).isFalse();
        assertThat(BatchTraceWorker.canReconcileSnapshots(List.of(), List.of(dummy))).isFalse();
        assertThat(BatchTraceWorker.canReconcileSnapshots(List.of(dummy), List.of(dummy))).isTrue();
    } // testCanReconcileSnapshots

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
}
