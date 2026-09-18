package cs1302.tracer.batch;

import cs1302.tracer.trace.PersistentGuestSession;

import static org.assertj.core.api.Assertions.assertThat;

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
        try (BatchTraceWorker worker = new BatchTraceWorker(5)) {
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
}
