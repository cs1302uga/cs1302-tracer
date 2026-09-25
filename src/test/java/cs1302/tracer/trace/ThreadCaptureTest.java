package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.ThreadGroupReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMStartEvent;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ThreadCaptureTest {
    static Stream<Arguments> states() {
        return Stream.of(
                Arguments.of(ThreadReference.THREAD_STATUS_ZOMBIE, "TERMINATED"),
                Arguments.of(ThreadReference.THREAD_STATUS_NOT_STARTED, "NEW"),
                Arguments.of(ThreadReference.THREAD_STATUS_RUNNING, "RUNNABLE"),
                Arguments.of(ThreadReference.THREAD_STATUS_SLEEPING, "SLEEPING"),
                Arguments.of(ThreadReference.THREAD_STATUS_MONITOR, "BLOCKED"),
                Arguments.of(ThreadReference.THREAD_STATUS_WAIT, "WAITING"),
                Arguments.of(ThreadReference.THREAD_STATUS_UNKNOWN, "UNKNOWN"));
    }

    @ParameterizedTest
    @MethodSource("states")
    void translatesGuestStatesWithoutReportingDebuggerSuspension(int status, String expected) {
        var thread = mirror(ThreadReference.class, Map.of("status", status));
        assertThat(ThreadCapture.state(thread)).isEqualTo(expected);
    }

    @Test
    void ignoresJvmServiceThreadDeaths() {
        var applicationGroup = mirror(ThreadGroupReference.class, Map.of());
        var main = mirror(ThreadReference.class, Map.of("threadGroup", applicationGroup));
        var service = mirror(ThreadReference.class, Map.of("uniqueID", 2L));
        var capture = new ThreadCapture();
        capture.observe(mirror(VMStartEvent.class, Map.of("thread", main)));
        assertThat(capture.observe(mirror(ThreadDeathEvent.class, Map.of("thread", service))))
                .isNull();
        assertThat(capture.isDying(service)).isFalse();
    }

    @Test
    void unsupportedVirtualThreadStopsBeforeCapturingAnIncompleteState() {
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            session.enableMultithread();
            var thread = mirror(ThreadReference.class, Map.of("isVirtual", true));
            var event = mirror(ThreadStartEvent.class, Map.of("thread", thread));
            assertThatThrownBy(() -> session.threadCapture().observe(event))
                    .isInstanceOf(TraceSession.Stopped.class).hasMessage("unsupported_virtual_thread");
            assertThat(session.stopReason()).isEqualTo("unsupported_virtual_thread");
            assertThat(session.snapshots()).isEmpty();
        }
    }

    @Test
    void threadChangesMustNotBeDiscardedAsDuplicateSnapshots() {
        var first = new ExecutionSnapshot.ThreadSnapshot(1L, "first", "RUNNABLE", List.of());
        var second = new ExecutionSnapshot.ThreadSnapshot(2L, "second", "WAITING", List.of());
        var baseline = snapshot(List.of(first), 1L);
        assertThat(DebugTraceHelper.isRedundantSnapshot(baseline, snapshot(List.of(first), 1L)))
                .isTrue();
        assertThat(DebugTraceHelper.isRedundantSnapshot(baseline, snapshot(List.of(first, second), 1L)))
                .isFalse();
        var both = snapshot(List.of(first, second), 1L);
        assertThat(DebugTraceHelper.isRedundantSnapshot(both, snapshot(List.of(first, second), 2L)))
                .isFalse();
    }

    private static ExecutionSnapshot snapshot(List<ExecutionSnapshot.ThreadSnapshot> threads, long id) {
        return new ExecutionSnapshot(List.of(), List.of(), Map.of(),
                OutputSlice.empty(), OutputSlice.empty(), Optional.empty(), "", 0,
                threads, id, "step_line");
    }
}
