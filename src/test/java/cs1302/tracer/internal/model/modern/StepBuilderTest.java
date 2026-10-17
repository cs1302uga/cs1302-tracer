package cs1302.tracer.internal.model.modern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cs1302.tracer.internal.serialize.ModernTraceSerializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Construction and collection ownership of modern steps. */
class StepBuilderTest {

    @Test
    void defaultsPreserveInputTrackingWithoutInventingExecutionFields() {
        Step step = Step.builder().build();
        assertThat(step).isEqualTo(new Step(0, 0, null, null, null, null, null, null, null, null));
        assertThat(step.toBuilder().build()).isEqualTo(step);
        assertThat(step.toBuilder().stdinConsumed(null).build().stdinConsumed()).isNull();
    }

    @Test
    void allFieldsAndSerializedShapeMatchCanonicalConstruction() {
        Variable variable = new Variable("count", "int", 3, false);
        StackFrame frame = new StackFrame("main", 7, "Main.java", true, null, List.of(variable));
        HeapObject object = HeapObject.ofString(19, "text");
        Step.ThreadState thread = new Step.ThreadState(8, "worker", "RUNNABLE", List.of(frame));
        Step expected = new Step(2, 7, "Main.java", "step_line", "main", List.of(frame), List.of(variable),
                Map.of("19", object), "out", "err", "read", 4, List.of(thread), 8L);
        Step actual = Step.builder().step(2).line(7).file("Main.java").event("step_line").method("main")
                .callStack(List.of(frame)).statics(List.of(variable)).heap(Map.of("19", object))
                .stdout("out").stderr("err").stdinConsumed("read").stdinOffset(4)
                .threads(List.of(thread)).triggeringThreadId(8L).build();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.toBuilder().build()).isEqualTo(actual);
        assertThat(ModernTraceSerializer.getGson(false).toJson(actual))
                .isEqualTo(ModernTraceSerializer.getGson(false).toJson(expected));
    }

    @Test
    void settersDetachImmediatelyAndPreserveNullEntriesAndOrder() {
        List<StackFrame> stack = new ArrayList<>(Arrays.asList((StackFrame) null));
        List<Variable> statics = new ArrayList<>(Arrays.asList((Variable) null));
        List<Step.ThreadState> threads = new ArrayList<>(Arrays.asList((Step.ThreadState) null));
        Map<String, HeapObject> heap = new LinkedHashMap<>();
        heap.put("second", null);
        heap.put("first", null);
        heap.put(null, null);
        Step.Builder builder = Step.builder().callStack(stack).statics(statics).threads(threads).heap(heap);
        stack.clear();
        statics.clear();
        threads.clear();
        heap.clear();
        Step first = builder.build();
        assertThat(first.callStack()).containsExactly((StackFrame) null);
        assertThat(first.statics()).containsExactly((Variable) null);
        assertThat(first.threads()).containsExactly((Step.ThreadState) null);
        assertThat(first.heap().keySet()).containsExactly("second", "first", null);
        assertThat(first.heap()).containsEntry("second", null);
        assertThatThrownBy(() -> first.callStack().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.statics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.threads().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.heap().clear()).isInstanceOf(UnsupportedOperationException.class);
        Step second = builder.callStack(null).statics(null).threads(null).heap(null).build();
        assertThat(second).isEqualTo(Step.builder().build());
        assertThat(first.heap()).hasSize(3);
    }

    @Test
    void copyBuilderDetachesConstructorCollectionsButSharesNestedValues() {
        List<Variable> locals = new ArrayList<>();
        StackFrame frame = new StackFrame("main", 1, null, true, null, locals);
        List<StackFrame> stack = new ArrayList<>(List.of(frame));
        List<Variable> statics = new ArrayList<>();
        Map<String, HeapObject> heap = new LinkedHashMap<>();
        heap.put("1", HeapObject.ofString(1, "value"));
        List<Step.ThreadState> threads = new ArrayList<>();
        Step original = new Step(1, 1, null, "step_line", "main", stack, statics, heap,
                "", "", "", 0, threads, null);
        Step.Builder builder = original.toBuilder();
        stack.clear();
        statics.add(null);
        heap.clear();
        threads.add(null);
        Step copied = builder.build();
        assertThat(original.callStack()).isEmpty();
        assertThat(original.statics()).containsExactly((Variable) null);
        assertThat(original.heap()).isEmpty();
        assertThat(original.threads()).containsExactly((Step.ThreadState) null);
        assertThat(copied.callStack()).containsExactly(frame);
        assertThat(copied.statics()).isEmpty();
        assertThat(copied.heap()).containsOnlyKeys("1");
        assertThat(copied.threads()).isEmpty();
        locals.add(new Variable("later", "int", 4, false));
        assertThat(copied.callStack().getFirst()).isSameAs(frame);
        assertThat(copied.callStack().getFirst().locals()).hasSize(1);
        assertThat(builder.stdout("new").build().stdout()).isEqualTo("new");
        assertThat(copied.stdout()).isEmpty();
    }
}
