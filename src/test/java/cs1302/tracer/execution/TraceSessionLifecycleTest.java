package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceSessionLifecycleTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void rejectsEachNegativeBudget(int index) {
        long[] values = new long[8];
        values[index] = -1;
        assertThatThrownBy(() -> new TraceLimits(values[0], values[1], values[2], values[3],
                values[4], values[5], values[6], values[7]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nonnegative");
    }

    @Test
    void nestedSessionCannotReplaceItsOwner() {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            assertThatThrownBy(() -> new TraceSession(
                    TraceLimits.unlimited(), InspectionPolicy.TRUSTED, false))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(TraceSession.current()).isSameAs(session);
        }
        assertThat(TraceSession.current()).isNull();
    }

    @Test
    void nestedEnumOptionsRestoreTheOuterSetting() throws Exception {
        var outer = TraceSession.withEvalEnumHash(false);
        try (outer) {
            var inner = TraceSession.withEvalEnumHash(true);
            try (inner) {
                assertThat(TraceSession.shouldEvalEnumHash()).isTrue();
            }
            assertThat(TraceSession.shouldEvalEnumHash()).isFalse();
        }
        assertThat(TraceSession.shouldEvalEnumHash()).isTrue();
    }

    @Test
    void interruptedOwnerCancelsWithoutClearingItsInterrupt() {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(session::check).isInstanceOf(TraceSession.Stopped.class)
                    .hasMessage("cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void referenceBudgetsDeduplicateWithinButNotAcrossSnapshots() {
        var limits = new TraceLimits(0, 0, 0, 1, 0, 0, 0, 0);
        try (var session = new TraceSession(limits, InspectionPolicy.FIELDS, true)) {
            session.beginSnapshot();
            assertThat(session.encounter(10)).isTrue();
            assertThat(session.encounter(10)).isFalse();
            session.beginSnapshot();
            assertThat(session.encounter(20)).isTrue();
            assertThatThrownBy(() -> session.encounter(30)).hasMessage("heap_limit");
            session.cancel();
            assertThatThrownBy(session::check).hasMessage("heap_limit");
        }
    }

    @Test
    void exactBoundaryIsAllowedAndExcessStops() {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            session.enforce(100, 100, "source_limit");
            session.enforce(1000, 0, "source_limit");
            assertThatThrownBy(() -> session.enforce(101, 100, "source_limit"))
                    .isInstanceOf(TraceSession.Stopped.class).hasMessage("source_limit");
        }
    }

    @Test
    void countsSnapshotsEvenIfPreviousOnesWereReplaced() {
        var limits = new TraceLimits(0, 1, 0, 0, 0, 0, 0, 0);
        try (var session = new TraceSession(limits, InspectionPolicy.FIELDS, false)) {
            session.beginSnapshot();
            var snapshot = new cs1302.tracer.trace.ExecutionSnapshot(
                    List.of(), List.of(), Map.of(), new byte[0], new byte[0]);
            session.commit(snapshot);
            assertThatThrownBy(session::beginSnapshot).hasMessage("snapshot_limit");
            assertThat(session.snapshots()).containsExactly(snapshot);
        }
    }
    private static class GuestProcess extends Process {
        boolean alive = true;
        boolean waited;
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { waited = true; return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            waited = true;
            if (Thread.interrupted()) throw new InterruptedException();
            return true;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
    }

    private static com.sun.jdi.VirtualMachine vm(GuestProcess process) {
        return (com.sun.jdi.VirtualMachine) java.lang.reflect.Proxy.newProxyInstance(
                com.sun.jdi.VirtualMachine.class.getClassLoader(),
                new Class<?>[] {com.sun.jdi.VirtualMachine.class},
                (self, method, args) -> method.getName().equals("process") ? process : null);
    }

    @Test
    void cancellationDuringLaunchTerminatesTheLateGuest() {
        var process = new GuestProcess();
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            session.cancel();
            assertThatThrownBy(() -> session.attach(vm(process))).hasMessage("cancelled");
            assertThat(process.alive).isFalse();
        }
    }

    @Test
    void closingLiveGuestWaitsForTerminationAndPreservesInterrupt() {
        for (boolean interrupted : List.of(false, true)) {
            var process = new GuestProcess();
            try {
                var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true);
                session.attach(vm(process));
                if (interrupted) Thread.currentThread().interrupt();
                session.close();
                assertThat(process.alive).isFalse();
                assertThat(process.waited).isTrue();
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
                assertThat(TraceSession.current()).isNull();
            } finally { Thread.interrupted(); }
        }
    }


    @Test
    void inheritedEnumConfigurationIsCapturedBySession() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            try (var scope = TraceSession.withEvalEnumHash(enabled);
                    var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
                assertThat(scope).isNotNull();
                assertThat(TraceSession.current()).isSameAs(session);
                assertThat(TraceSession.shouldEvalEnumHash()).isEqualTo(enabled);
            }
        }
        JobOptions options = new JobOptions();
        new picocli.CommandLine(options).parseArgs("--no-eval-enum-hash=false");
        assertThat(options.evalEnumHash).isTrue();
    }

    @Test
    void failuresAreClassifiedByPhaseWithoutRequiringAGuest() {
        for (String phase : List.of("source", "compile", "trace", "serialize")) {
            try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
                session.phase(phase);
                session.phase(phase);
                var result = session.result("modern", null, new IllegalArgumentException("bad input"));
                assertThat(result.status()).isEqualTo("failed");
                assertThat(result.stopReason()).isEqualTo(
                        phase.equals("source") || phase.equals("compile") ? "compile_error" : "tracer_error");
                assertThat(result.diagnostics()).anyMatch(message -> message.contains("bad input"));
            }
        }
    }

    @Test
    void resultDoesNotAskLiveProcessForExitValue() {
        var process = new GuestProcess();
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            session.attach(vm(process));
            assertThat(session.result("modern", null, null).counters()).doesNotContainKey("guestExitCode");
        }
    }

    @Test
    void finalOutputUpdatesOnlyTheLastRetainedLine() {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, false)) {
            var first = snapshot(1);
            var last = snapshot(2);
            session.commit(first);
            session.finishOutput(); // no drainers yet
            session.commit(last);
            try (var err = new cs1302.tracer.trace.StreamDrainer(java.io.InputStream.nullInputStream());
                    var out = new cs1302.tracer.trace.StreamDrainer(new java.io.ByteArrayInputStream(new byte[] {65}))) {
                err.waitForEof(1000);
                out.waitForEof(1000);
                session.finishOutput();
                assertThat(session.snapshots().getFirst()).isSameAs(first);
                assertThat(session.snapshots().getLast().stdout()).containsExactly((byte) 65);
                // Replacement must remove the updated snapshot, not the old identity.
                session.commit(snapshot(2));
                assertThat(session.snapshots()).hasSize(2);
            }
        }
    }

    private static cs1302.tracer.trace.ExecutionSnapshot snapshot(int line) {
        return new cs1302.tracer.trace.ExecutionSnapshot(List.of(
                new cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot("main", line, List.of(), java.util.Optional.empty())),
                List.of(), Map.of(), new byte[0], new byte[0]);
    }

    @Test
    void snapshotCounterHonorsWriterFlushAndCloseContract() throws Exception {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            Class<?> type = Class.forName("cs1302.tracer.execution.TraceSession$SnapshotCounter");
            var constructor = type.getDeclaredConstructor(TraceSession.class);
            constructor.setAccessible(true);
            try (java.io.Writer writer = (java.io.Writer) constructor.newInstance(session)) {
                writer.write("hello");
                writer.flush();
                var bytes = type.getDeclaredField("bytes");
                bytes.setAccessible(true);
                assertThat(bytes.getLong(writer)).isEqualTo(15);
            }
        }
    }

    @Test
    void completedResultLetsWatchdogExitWithoutInterruption() throws Exception {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            session.phase("trace");
            assertThat(session.result("modern", null, null).complete()).isTrue();
            var field = TraceSession.class.getDeclaredField("watchdog");
            field.setAccessible(true);
            Thread watchdog = (Thread) field.get(session);
            watchdog.join(1000);
            assertThat(watchdog.isAlive()).isFalse();
            assertThat(watchdog.isInterrupted()).isFalse();
        }
    }
}
