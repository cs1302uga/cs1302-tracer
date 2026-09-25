package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.*;

import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.ThreadSnapshot;
import cs1302.tracer.trace.OutputSlice;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThreadSerializationTest {
    @Test
    void preservesWorkerArgumentsNamedArgs() {
        var field = new ExecutionSnapshot.Field(false, "int", "args",
                new cs1302.tracer.trace.TraceValue.Primitive.Integer(7));
        var frame = new StackSnapshot("work", 7, List.of(field), Optional.empty());
        var snapshot = new ExecutionSnapshot(List.of(frame), List.of(), Map.of(),
                OutputSlice.empty(), OutputSlice.empty(), Optional.empty(), "", 0,
                List.of(new ThreadSnapshot(1, "worker", "RUNNABLE", List.of(frame))), 1L, "step_line");
        var step = new ModernTraceSerializer(true, false, false).createStep(snapshot, 1, false);
        assertThat(step.callStack().getFirst().locals()).hasSize(1);
        assertThat(step.threads().getFirst().callStack().getFirst().locals()).hasSize(1);
    }

    @Test
    void serializesThreadIdentityStatesAndEmptyTerminatedStacks() {
        var frame = new StackSnapshot("work", 7, List.of(), Optional.empty(), Optional.of("Main.java"));
        var snapshot = new ExecutionSnapshot(List.of(frame), List.of(), Map.of(),
                OutputSlice.empty(), OutputSlice.empty(), Optional.of("Main.java"), "", 0,
                List.of(new ThreadSnapshot(1, "worker", "RUNNABLE", List.of(frame, frame)),
                        new ThreadSnapshot(2, "done", "TERMINATED", List.of())), 1L, "thread_death");
        var step = new ModernTraceSerializer(false, false, false).createStep(snapshot, 1, true);
        assertThat(step.triggeringThreadId()).isEqualTo(1);
        assertThat(step.event()).isEqualTo("thread_death");
        assertThat(step.threads().getFirst().callStack()).hasSize(2);
        assertThat(step.threads().getFirst().callStack().getFirst().isHighlighted()).isFalse();
        assertThat(step.threads().getFirst().callStack().getLast().isHighlighted()).isTrue();
        assertThat(step.threads().getLast().callStack()).isEmpty();
        var json = ModernTraceSerializer.getGson(false).toJson(step);
        assertThat(json).contains("\"triggeringThreadId\":1", "\"state\":\"TERMINATED\"");
        assertThatThrownBy(() -> new PyTutorSerializer(false, false, false).createTraceStep(snapshot))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modern");
    }
}
