package cs1302.tracer.internal.model.pytutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Construction and shallow collection ownership of Python Tutor steps. */
class TraceStepBuilderTest {

    @Test
    void defaultsPreserveInputTrackingWithoutInventingExecutionFields() {
        TraceStep step = TraceStep.builder().build();
        assertThat(step).isEqualTo(new TraceStep(null, null, null, null, 0, null, null, null, null, null, null));
        assertThat(step.toBuilder().build()).isEqualTo(step);
        assertThat(step.toBuilder().stdinConsumed(null).build().stdinConsumed()).isNull();
    }

    @Test
    void allFieldsAndSerializedShapeMatchCanonicalConstruction() {
        RenderStackFrame frame = new RenderStackFrame("main", Map.of(), Map.of(), List.of(), List.of(),
                true, false, false, "0", 0);
        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("missing", null);
        globals.put("count", 5);
        Map<String, Object> attrs = Map.of("count", Map.of("type", "int"));
        Map<String, Object> heap = Map.of("1", List.of("INSTANCE", "Thing"));
        Map<String, Object> heapAttrs = Map.of("1", Map.of("type", "Thing"));
        List<String> names = List.of("missing", "count");
        TraceStep expected = new TraceStep("out", "err", "step_line", "main", 12, List.of(frame),
                globals, attrs, names, heap, heapAttrs, "Main.java", "read", 4);
        TraceStep actual = TraceStep.builder().stdout("out").stderr("err").event("step_line")
                .funcName("main").line(12).stackToRender(List.of(frame)).globals(globals).globalsAttrs(attrs)
                .orderedGlobals(names).heap(heap).heapAttrs(heapAttrs).file("Main.java")
                .stdinConsumed("read").stdinOffset(4).build();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.toBuilder().build()).isEqualTo(actual);
        assertThat(PyTutorSerializer.getGson().toJson(actual))
                .isEqualTo(PyTutorSerializer.getGson().toJson(expected));
        JsonObject json = PyTutorSerializer.getGson().toJsonTree(actual).getAsJsonObject();
        assertThat(json.getAsJsonObject("globals").has("missing")).isTrue();
        assertThat(json.getAsJsonObject("globals").get("missing").isJsonNull()).isTrue();
    }

    @Test
    void settersDetachContainersImmediatelyAndKeepNullsAndOrdering() {
        List<RenderStackFrame> frames = new ArrayList<>(Arrays.asList((RenderStackFrame) null));
        List<String> names = new ArrayList<>(Arrays.asList("second", null, "first"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("second", null);
        values.put("first", 1);
        values.put(null, null);
        TraceStep.Builder builder = TraceStep.builder().stackToRender(frames).orderedGlobals(names)
                .globals(values).globalsAttrs(values).heap(values).heapAttrs(values);
        frames.clear();
        names.clear();
        values.clear();
        TraceStep first = builder.build();
        assertThat(first.stackToRender()).containsExactly((RenderStackFrame) null);
        assertThat(first.orderedGlobals()).containsExactly("second", null, "first");
        for (Map<String, Object> map : List.of(first.globals(), first.globalsAttrs(), first.heap(),
                first.heapAttrs())) {
            assertThat(map.keySet()).containsExactly("second", "first", null);
            assertThat(map).containsEntry("second", null);
            assertThatThrownBy(map::clear).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> first.stackToRender().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.orderedGlobals().clear()).isInstanceOf(UnsupportedOperationException.class);
        TraceStep second = builder.stackToRender(null).orderedGlobals(null).globals(null).globalsAttrs(null)
                .heap(null).heapAttrs(null).build();
        assertThat(second).isEqualTo(TraceStep.builder().build());
        assertThat(first.globals()).hasSize(3);
    }

    @Test
    void copyBuilderDetachesConstructorContainersButLeavesNestedObjectsShared() {
        List<Object> tuple = new ArrayList<>(List.of("INSTANCE", "Thing"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("1", tuple);
        List<RenderStackFrame> frames = new ArrayList<>();
        List<String> names = new ArrayList<>(List.of("1"));
        TraceStep original = new TraceStep("out", "err", "step_line", "main", 1, frames,
                values, values, names, values, values);
        TraceStep.Builder builder = original.toBuilder();
        values.clear();
        frames.add(null);
        names.clear();
        TraceStep copied = builder.build();
        assertThat(original.globals()).isEmpty();
        assertThat(original.stackToRender()).containsExactly((RenderStackFrame) null);
        assertThat(original.orderedGlobals()).isEmpty();
        assertThat(copied.stackToRender()).isEmpty();
        assertThat(copied.orderedGlobals()).containsExactly("1");
        tuple.add("later");
        for (Map<String, Object> map : List.of(copied.globals(), copied.globalsAttrs(), copied.heap(),
                copied.heapAttrs())) {
            assertThat(map.get("1")).isSameAs(tuple);
            assertThat(map.get("1")).isEqualTo(List.of("INSTANCE", "Thing", "later"));
        }
        assertThat(builder.stdout("changed").build().stdout()).isEqualTo("changed");
        assertThat(copied.stdout()).isEqualTo("out");
    }
}
