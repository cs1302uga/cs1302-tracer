package cs1302.tracer.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import cs1302.tracer.internal.CompilationHelper;
import cs1302.tracer.internal.SubmittedSources;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.internal.trace.OutputSlice;
import cs1302.tracer.internal.trace.StreamDrainer;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TraceCompletionTest {

    private static final SubmittedSources SOURCE = CompilationHelper.parseSources(
            "public class Main { public static void main(String[] args) {} }").submitted();

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void envelopeDistinguishesUnavailableFromAvailableEmptyHistory(TraceFormat format) {
        try (var session = session(true)) {
            session.phase("compile");
            var failure = new IOException("compilation failed");
            var unavailable = TraceCompletion.envelope(SOURCE, "", job(format, false, true, false),
                    session, null, failure);
            assertThat(unavailable.payload()).isNull();
            assertThat(unavailable.failure()).isSameAs(failure);

            session.attach(new GuestProcess(), false);
            var available = TraceCompletion.envelope(SOURCE, "", job(format, false, true, false),
                    session, null, failure);
            assertThat(available.failure()).isSameAs(failure);
            assertThat(json(available.payload()).getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace"))
                    .isEmpty();
            var result = session.result(format.toString(), available.payload(), available.failure());
            assertThat(result.phase()).isEqualTo("compile");
            assertThat(result.diagnostics()).contains(failure.toString());
        }
    }

    @Test
    void envelopePrefersSuppliedHistoryEvenWithoutAGuest() {
        try (var session = session(true)) {
            var retained = snapshot(1, "retained");
            var supplied = List.of(snapshot(2, "supplied"));
            session.commit(retained);
            var completed = TraceCompletion.envelope(SOURCE, "input", job(TraceFormat.MODERN, false, true, false),
                    session, supplied, null, (source, stdin, job, snapshots) -> {
                        assertThat(source).isEqualTo(SOURCE);
                        assertThat(stdin).isEqualTo("input");
                        assertThat(snapshots).isSameAs(supplied);
                        return snapshots;
                    });
            assertThat(completed.payload()).isSameAs(supplied);
            assertThat(completed.failure()).isNull();
            assertThat(session.snapshots()).containsExactly(retained);
        }
    }

    @Test
    void envelopeRecoversRawHistoryForTargetedJobs() {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            var first = snapshot(1, "");
            var last = snapshot(2, "finished");
            session.commit(first);
            session.commit(last);
            var completed = TraceCompletion.envelope(SOURCE, "", job(TraceFormat.MODERN, false, true, false),
                    session, null, null, (source, stdin, job, snapshots) -> {
                        assertThat(snapshots).containsExactly(first, last);
                        assertThat(snapshots.getFirst().stdout()).isEmpty();
                        return snapshots;
                    });
            assertThat(completed.payload()).isEqualTo(session.snapshots());
            assertThat(session.targetedSnapshots().getFirst().stdoutSlice().asUtf8String()).isEqualTo("finished");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void envelopeRenderingFailureReplacesAnyEarlierFailure(boolean alreadyFailed) {
        try (var session = session(true)) {
            session.phase("compile");
            Throwable original = alreadyFailed ? new IOException("execution failed") : null;
            var rendering = new IllegalStateException("serializer unavailable");
            var completed = TraceCompletion.envelope(SOURCE, "", job(TraceFormat.MODERN, false, true, false),
                    session, List.of(), original, (source, stdin, job, snapshots) -> {
                        throw rendering;
                    });
            assertThat(completed.payload()).isNull();
            assertThat(completed.failure()).isSameAs(rendering);
            assertThat(rendering.getSuppressed()).isEmpty();
            var result = session.result("modern", completed.payload(), completed.failure());
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.phase()).isEqualTo("serialize");
            assertThat(result.stopReason()).isEqualTo("tracer_error");
            assertThat(result.diagnostics()).contains(rendering.toString());
        }
    }

    @Test
    void completionDoesNotCatchRenderingErrorsOrHandleInterruptions() {
        try (var session = session(true)) {
            var error = new AssertionError("renderer failed");
            assertThatThrownBy(() -> TraceCompletion.envelope(SOURCE, "",
                    job(TraceFormat.MODERN, true, true, false), session, List.of(), null,
                    (source, stdin, job, snapshots) -> { throw error; })).isSameAs(error);

            var interrupted = new InterruptedException("guest interrupted");
            var completed = TraceCompletion.envelope(SOURCE, "", job(TraceFormat.MODERN, true, true, false),
                    session, null, interrupted);
            assertThat(completed.failure()).isSameAs(interrupted);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(session.isStopped()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void successfulChronologicalWorkerUsesReturnedCapture(TraceFormat format) {
        try (var session = session(true)) {
            session.phase("compile");
            session.commit(snapshot(1, "retained"));
            var captured = new TraceDispatch.Chronological(List.of(snapshot(2, "returned")));
            var payload = TraceCompletion.workerSuccess(SOURCE, "", job(format, true, true, false), session, captured);
            var steps = json(payload).getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
            assertThat(steps).hasSize(1);
            assertThat(steps.get(0).getAsJsonObject().get("stdout").getAsString()).isEqualTo("returned");
            assertThat(session.result(format.toString(), payload, null).phase()).isEqualTo("serialize");
            assertThat(session.snapshots().getFirst().stdoutSlice().asUtf8String()).isEqualTo("retained");
        }
    }

    @Test
    void multithreadWorkerUsesFinalizedRetainedHistory() {
        try (var session = session(true);
                var err = new StreamDrainer(InputStream.nullInputStream());
                var out = new StreamDrainer(new ByteArrayInputStream("final output".getBytes(StandardCharsets.UTF_8)))) {
            session.enableMultithread();
            err.waitForEof(1000);
            out.waitForEof(1000);
            session.commit(snapshot(1, ""));
            var captured = new TraceDispatch.Chronological(List.of(snapshot(2, "stale returned state")));
            var payload = TraceCompletion.workerSuccess(SOURCE, "", job(TraceFormat.MODERN, true, true, true),
                    session, captured);
            var steps = json(payload).getAsJsonArray("steps");
            assertThat(steps).hasSize(1);
            assertThat(steps.get(0).getAsJsonObject().get("stdout").getAsString()).isEqualTo("final output");
            assertThat(session.snapshots().getFirst().stack().getLast().methodLine()).isEqualTo(1);
            assertThat(session.result("modern", payload, null).phase()).isEqualTo("serialize");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void targetedWorkerDelegatesLatestAndAccumulatedProjectionToRetention(boolean accumulate) {
        try (var session = session(accumulate)) {
            session.commit(snapshot(1, "first"));
            session.commit(snapshot(2, "middle"));
            session.commit(snapshot(1, "last"));
            var canonical = session.snapshots();
            var payload = TraceCompletion.workerSuccess(SOURCE, "", job(TraceFormat.MODERN, false, accumulate, false),
                    session, new TraceDispatch.FinalState(snapshot(9, "ignored")),
                    (source, stdin, job, snapshots) -> {
                        assertThat(snapshots).hasSize(accumulate ? 3 : 2);
                        assertThat(snapshots.getLast().stdoutSlice().asUtf8String()).isEqualTo("last");
                        assertThat(snapshots.get(snapshots.size() - 2).stdoutSlice().asUtf8String()).isEqualTo("last");
                        return snapshots;
                    });
            assertThat(payload).isEqualTo(session.targetedSnapshots());
            assertThat(session.snapshots()).isEqualTo(canonical);
            assertThat(session.result("modern", payload, null).phase()).isEqualTo("serialize");
        }
    }

    @Test
    void workerRecoveryRequiresAvailableNonemptyRawHistory() {
        try (var session = session(true)) {
            var failure = new IOException("guest failed");
            var job = job(TraceFormat.MODERN, false, true, false);
            assertThat(TraceCompletion.workerRecovery(SOURCE, "", job, session, failure)).isNull();
            session.commit(snapshot(1, "not yet attached"));
            assertThat(TraceCompletion.workerRecovery(SOURCE, "", job, session, failure)).isNull();
        }
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            assertThat(TraceCompletion.workerRecovery(SOURCE, "", job(TraceFormat.MODERN, false, true, false),
                    session, new IOException("no snapshot"))).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void workerRecoveryPreservesPhaseAndProfileProjection(boolean chronological) {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            session.phase("compile");
            var first = snapshot(1, "first");
            var last = snapshot(2, "last");
            session.commit(first);
            session.commit(last);
            var failure = new IOException("execution failed");
            var payload = TraceCompletion.workerRecovery(SOURCE, "",
                    job(TraceFormat.MODERN, chronological, true, false), session, failure,
                    (source, stdin, job, snapshots) -> {
                        assertThat(snapshots).hasSize(2);
                        assertThat(snapshots.getFirst().stdoutSlice().asUtf8String())
                                .isEqualTo(chronological ? "first" : "last");
                        return snapshots;
                    });
            assertThat(payload).isNotNull();
            assertThat(session.snapshots()).containsExactly(first, last);
            assertThat(session.result("modern", payload, failure).phase()).isEqualTo("compile");
        }
    }

    @Test
    void workerRecoveryPreservesTraceBudgetCanonicalPrefixes() {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            session.commit(snapshot(1, "first"));
            session.commit(snapshot(2, "last"));
            session.stop("trace_limit");
            var failure = new TraceSession.Stopped("trace_limit");
            var payload = TraceCompletion.workerRecovery(SOURCE, "", job(TraceFormat.MODERN, false, true, false),
                    session, failure);
            assertThat(json(payload).getAsJsonArray("steps").get(0).getAsJsonObject().get("stdout").getAsString())
                    .isEqualTo("first");
            assertThat(session.result("modern", payload, failure).stopReason()).isEqualTo("trace_limit");
        }
    }

    @Test
    void workerRecoverySuppressesRenderingFailureOntoOriginalFailure() {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            session.phase("compile");
            session.commit(snapshot(1, "partial"));
            var failure = new IOException("execution failed");
            var rendering = new IllegalStateException("serializer unavailable");
            var payload = TraceCompletion.workerRecovery(SOURCE, "", job(TraceFormat.MODERN, false, true, false),
                    session, failure, (source, stdin, job, snapshots) -> { throw rendering; });
            assertThat(payload).isNull();
            assertThat(failure.getSuppressed()).containsExactly(rendering);
            var result = session.result("modern", payload, failure);
            assertThat(result.phase()).isEqualTo("compile");
            assertThat(result.diagnostics()).contains(failure.toString()).doesNotContain(rendering.toString());
        }
    }

    @Test
    void workerCanRetryAfterSuccessfulPathRenderingFailure() {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            var snapshot = snapshot(1, "partial");
            session.commit(snapshot);
            var job = job(TraceFormat.MODERN, true, true, false);
            var attempts = new AtomicInteger();
            var rendering = new IllegalStateException("first rendering failed");
            TraceCompletion.SequenceRenderer renderer = (source, stdin, options, snapshots) -> {
                assertThat(snapshots).containsExactly(snapshot);
                if (attempts.incrementAndGet() == 1) {
                    throw rendering;
                }
                return snapshots;
            };
            assertThatThrownBy(() -> TraceCompletion.workerSuccess(SOURCE, "", job, session,
                    new TraceDispatch.Chronological(List.of(snapshot)), renderer)).isSameAs(rendering);
            var payload = TraceCompletion.workerRecovery(SOURCE, "", job, session, rendering, renderer);
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(payload).isEqualTo(List.of(snapshot));
            var result = session.result("modern", payload, rendering);
            assertThat(result.phase()).isEqualTo("serialize");
            assertThat(result.trace()).isEqualTo(payload);
            assertThat(result.complete()).isFalse();
            assertThat(rendering.getSuppressed()).isEmpty();
        }
    }

    @Test
    void cleanupFailureStillAllowsWorkerRecoveryBeforeResultClassification() {
        try (var session = session(true)) {
            session.attach(new GuestProcess(), false);
            var snapshot = snapshot(1, "completed capture");
            session.commit(snapshot);
            var job = job(TraceFormat.PYTUTOR, true, true, false);
            var cleanup = new IOException("preparation cleanup failed");
            try {
                try (AutoCloseable prepared = () -> { throw cleanup; }) {
                    assertThat(prepared).isNotNull();
                    assertThat(TraceCompletion.workerSuccess(SOURCE, "", job, session,
                            new TraceDispatch.Chronological(List.of(snapshot)))).isNotNull();
                }
                throw new AssertionError("Expected preparation cleanup failure");
            } catch (Exception failure) {
                assertThat(failure).isSameAs(cleanup);
                var payload = TraceCompletion.workerRecovery(SOURCE, "", job, session, failure);
                assertThat(json(payload).getAsJsonArray("trace")).hasSize(1);
                var result = session.result("pytutor", payload, failure);
                assertThat(result.phase()).isEqualTo("serialize");
                assertThat(result.diagnostics()).contains(cleanup.toString());
            }
        }
    }

    private static TraceSession session(boolean accumulate) {
        return new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, accumulate);
    }

    private static TraceJob job(TraceFormat format, boolean chronological, boolean accumulate, boolean multithread) {
        return TraceJob.resolve(TraceJob.Profile.NDJSON, format, TypeStyle.FQN, TraceLimits.unlimited(),
                InspectionPolicy.FIELDS, List.of(), chronological, accumulate, multithread,
                false, false, false, false);
    }

    private static ExecutionSnapshot snapshot(int line, String stdout) {
        return new ExecutionSnapshot(List.of(new StackSnapshot("main", line, List.of(), Optional.empty())),
                List.of(), Map.of(), OutputSlice.from(stdout.getBytes(StandardCharsets.UTF_8)), OutputSlice.empty(),
                Optional.of("Main.java"), "", 0);
    }

    private static JsonObject json(Object payload) {
        return PyTutorSerializer.getGson(false).toJsonTree(payload).getAsJsonObject();
    }

    private static final class GuestProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public void destroy() {}

    }

}
