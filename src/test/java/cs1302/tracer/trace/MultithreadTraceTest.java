package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import cs1302.tracer.CompilationHelper;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.execution.TraceSession;
import cs1302.tracer.serialize.ModernTraceSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
class MultithreadTraceTest {
    private TraceResult trace(String source, TraceLimits limits) throws Exception {
        try (TraceSession session = new TraceSession(limits, InspectionPolicy.TRUSTED, true)) {
            session.enableMultithread();
            Throwable failure = null;
            try (var compiled = CompilationHelper.compile(source, Optional.empty())) {
                var units = CompilationHelper.parseMultiFileStream(source).stream()
                        .map(CompilationHelper.SourceFile::ast).toList();
                session.phase("trace");
                var returned = DebugTraceHelper.traceChronological(compiled,
                        DebugTraceHelper.getValidBreakpointLines(compiled), units, true);
                assertThat(returned).extracting(ExecutionSnapshot::event).containsExactlyElementsOf(
                        session.snapshots().stream().map(ExecutionSnapshot::event).toList());
                session.finishOutput();
            } catch (Exception caught) {
                failure = caught;
            }
            var payload = new ModernTraceSerializer(false, false, false)
                    .createTrace(source, session.snapshots());
            return session.result("modern", payload, failure);
        }
    }

    private static TraceLimits limits(long timeout, long threads, long frames, long bytes) {
        return new TraceLimits(timeout, 1000, 1_048_576, 20_000, 500_000,
                134_217_728, 1_048_576, 128, threads, frames, bytes);
    }

    private static cs1302.tracer.model.modern.Trace payload(TraceResult result) {
        return (cs1302.tracer.model.modern.Trace) result.trace();
    }

    @ParameterizedTest
    @ValueSource(ints = {34, 35, 36, 37, 38, 39, 40, 41})
    void representativeExamplesPreserveThreadAndJobSemantics(int example) throws Exception {
        String source = Files.readString(Path.of("examples/example" + example + "/Driver.java"));
        var result = trace(source, limits(example == 41 ? 5000 : 20000, 32, 1024, 16_777_216));
        if (example == 39) {
            assertThat(result.stopReason()).isEqualTo("guest_exception");
            assertThat(result.stdout()).contains("survivor completed");
        } else if (example == 41) {
            assertThat(result.stopReason()).isEqualTo("timeout");
            assertThat(result.stdout()).contains("task completed");
        } else {
            assertThat(result.complete()).as("%s: %s", result.stopReason(), result.diagnostics()).isTrue();
        }
        var steps = payload(result).steps();
        assertThat(steps).isNotEmpty().allSatisfy(step -> {
            assertThat(step.threads()).isNotNull();
            assertThat(step.triggeringThreadId()).isNotNull();
            assertThat(step.threads()).anySatisfy(thread ->
                    assertThat(thread.id()).isEqualTo(step.triggeringThreadId()));
        });
        assertThat(steps).anySatisfy(step -> assertThat(step.threads().size()).isGreaterThan(1));
        assertThat(steps).anySatisfy(step -> assertThat(step.event()).isEqualTo("thread_start"));
        if (example != 41) {
            assertThat(steps).anySatisfy(step -> assertThat(step.event()).isEqualTo("thread_death"));
        }
        if (example == 35) {
            assertThat(result.stdout()).isEqualTo("6\n");
            var refs = steps.stream().flatMap(step -> step.threads().stream())
                    .flatMap(t -> t.callStack().stream()).flatMap(f -> f.locals().stream())
                    .filter(v -> v.name().equals("shared")).map(v -> v.value()).distinct().toList();
            assertThat(refs).hasSize(1);
            assertThat(steps.stream().flatMap(step -> step.threads().stream())
                    .filter(t -> t.callStack().stream().flatMap(f -> f.locals().stream())
                            .anyMatch(v -> v.name().equals("shared")))
                    .map(t -> t.id()).distinct().count()).isEqualTo(2);

        }
        if (example == 40) {
            assertThat(result.stdout()).contains("worker completed");
        }
    }

    @Test
    void preservesSubmittedThreadSubclassFieldsWithoutJvmBookkeeping() throws Exception {
        var result = trace("""
                public class Main {
                    static class Worker extends Thread {
                        int answer = 42;
                        public void run() {
                            System.out.println(answer);
                        }
                    }
                    public static void main(String[] args) throws InterruptedException {
                        Worker worker = new Worker();
                        worker.start();
                        worker.join();
                    }
                }
                """, limits(10000, 16, 1024, 16_777_216));
        assertThat(result.complete()).as("%s: %s; %s", result.stopReason(), result.diagnostics(),
                result.counters()).isTrue();
        assertThat(result.stdout()).isEqualTo("42\n");
        assertThat(payload(result).steps()).allSatisfy(step -> assertThat(step.statics()).isEmpty());
        var workers = payload(result).steps().stream().flatMap(step -> step.heap().values().stream())
                .filter(object -> object.type().equals("Main.Worker")
                        || object.type().equals("Main$Worker")).toList();
        assertThat(workers).isNotEmpty().allSatisfy(worker ->
                assertThat(worker.fields()).extracting(field -> field.name()).containsExactly("answer"));
        assertThat(workers).anySatisfy(worker ->
                assertThat(worker.fields().getFirst().value()).isEqualTo(42));
    }

    @Test
    void recordsWorkerExceptionsThrownInsideTheJdk() throws Exception {
        var result = trace("""
                public class Main {
                    public static void main(String[] args) throws InterruptedException {
                        Thread worker = new Thread(() -> Integer.parseInt("invalid"), "parser");
                        worker.start();
                        worker.join();
                        System.out.println("survived");
                    }
                }
                """, limits(10000, 16, 1024, 16_777_216));
        assertThat(result.stopReason()).isEqualTo("guest_exception");
        assertThat(result.stdout()).isEqualTo("survived\n");
        assertThat(result.diagnostics()).anySatisfy(value ->
                assertThat(value).contains("NumberFormatException", "parser"));
        assertThat(payload(result).steps()).anySatisfy(step ->
                assertThat(step.event()).isEqualTo("exception"));
    }

    @Test
    void rejectsCaptureApisThatCannotPreserveThreadHistory() throws Exception {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, false)) {
            assertThatThrownBy(session::enableMultithread).hasMessageContaining("accumulating");
        }
        try (var guest = PersistentGuestSession.create();
                var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            session.enableMultithread();
            assertThatThrownBy(() -> DebugTraceHelper.traceWithSpecs(null, null, null, ""))
                    .hasMessageContaining("chronological");
            assertThatThrownBy(() -> guest.traceChronologicalWithSpecs(null, null, null, true, ""))
                    .hasMessageContaining("dedicated guest");
        }
    }

    @Test
    void rejectsVirtualThreadsAndRetainsPartialTrace() throws Exception {
        var result = trace("""
                public class Main {
                    public static void main(String[] args) throws InterruptedException {
                        Thread.startVirtualThread(() -> System.out.println("virtual")).join();
                    }
                }
                """, limits(10000, 16, 1024, 16_777_216));
        assertThat(result.stopReason()).isEqualTo("unsupported_virtual_thread");
        assertThat(payload(result).steps()).isNotEmpty();
    }

    @Test
    void threadLimitPreservesOnlyCompleteSnapshots() throws Exception {
        var source = Files.readString(Path.of("examples/example34/Driver.java"));
        var result = trace(source, limits(10000, 1, 1024, 16_777_216));
        assertThat(result.stopReason()).isEqualTo("thread_limit");
        assertThat(payload(result).steps()).isNotEmpty()
                .allSatisfy(step -> assertThat(step.threads()).hasSize(1));
    }

    @Test
    void frameAndSnapshotLimitsStopCapture() throws Exception {
        var source = Files.readString(Path.of("examples/example34/Driver.java"));
        assertThat(trace(source, limits(10000, 16, 1, 16_777_216)).stopReason())
                .isEqualTo("frame_limit");
        assertThat(trace(source, limits(10000, 16, 1024, 1)).stopReason())
                .isEqualTo("snapshot_byte_limit");
    }
}
