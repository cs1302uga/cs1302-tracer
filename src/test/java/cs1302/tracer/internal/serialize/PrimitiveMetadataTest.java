package cs1302.tracer.internal.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.ExecutionSnapshot.Field;
import cs1302.tracer.internal.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.internal.trace.TraceValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrimitiveMetadataTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesValuesAndPlacesMetadataInBothFormats(boolean enabled) {
        TraceValue.Primitive.Byte minusOne = new TraceValue.Primitive.Byte((byte) -1);
        List<Field> fields = List.of(new Field(false, "byte", "x", minusOne),
            new Field(false, "Object", "x", new TraceValue.Null()),
            new Field(false, "Byte", "box", new TraceValue.Reference(3L)));
        Map<Long, TraceValue> heap = new LinkedHashMap<>();
        heap.put(1L, new TraceValue.Object("Example", fields));
        heap.put(2L, new TraceValue.List("byte[]", List.of(minusOne, new TraceValue.Null())));
        heap.put(3L, minusOne);
        heap.put(4L, new TraceValue.String("text"));
        heap.put(5L, new TraceValue.Collection(List.of(minusOne, new TraceValue.Reference(3L))));
        heap.put(6L, new TraceValue.Map(Map.of(new TraceValue.Reference(3L), minusOne)));
        ExecutionSnapshot snapshot = new ExecutionSnapshot(
            List.of(new StackSnapshot("main", 5, fields, Optional.empty())), fields, heap, new byte[0], new byte[0]);
        ModernTraceSerializer modern = new ModernTraceSerializer(false, false, false, TypeStyle.FQN, enabled);
        PyTutorSerializer pytutor = new PyTutorSerializer(false, false, false, TypeStyle.FQN, enabled);
        JsonObject m = JsonParser.parseString(ModernTraceSerializer.getGson().toJson(modern.createStep(snapshot, 1, true)))
            .getAsJsonObject();
        JsonObject p = JsonParser.parseString(PyTutorSerializer.getGson().toJson(pytutor.createTraceStep(snapshot, true)))
            .getAsJsonObject();
        JsonObject local = m.getAsJsonArray("callStack").get(0).getAsJsonObject().getAsJsonArray("locals")
            .get(0).getAsJsonObject();
        JsonObject pframe = p.getAsJsonArray("stack_to_render").get(0).getAsJsonObject();
        assertThat(local.get("value").getAsInt()).isEqualTo(-1);
        assertThat(m.getAsJsonObject("heap").getAsJsonObject("2").getAsJsonArray("elements").get(0).getAsInt())
            .isEqualTo(-1);
        assertThat(p.getAsJsonObject("heap").getAsJsonArray("2").get(1).getAsInt()).isEqualTo(-1);
        assertThat(local.has("binary")).isEqualTo(enabled);
        assertThat(pframe.getAsJsonObject("locals_attrs").getAsJsonObject("box").has("binary")).isFalse();
        if (!enabled) {
            assertThat(m.toString()).doesNotContain("binary", "elementBinary");
            assertThat(p.toString()).doesNotContain("binary", "elementBinary", "fieldBinary");
            return;
        }
        JsonObject descriptor = JsonParser.parseString(
            "{\"type\":\"byte\",\"encoding\":\"twos-complement\",\"bits\":\"11111111\"}").getAsJsonObject();
        assertThat(local.get("binary")).isEqualTo(descriptor);
        assertThat(m.getAsJsonArray("statics").get(0).getAsJsonObject().get("binary")).isEqualTo(descriptor);
        assertThat(m.getAsJsonObject("heap").getAsJsonObject("1").getAsJsonArray("fields").get(0).getAsJsonObject()
            .get("binary")).isEqualTo(descriptor);
        assertThat(m.getAsJsonObject("heap").getAsJsonObject("3").get("binary")).isEqualTo(descriptor);
        for (String id : List.of("2", "5")) {
            var mb = m.getAsJsonObject("heap").getAsJsonObject(id).getAsJsonArray("elementBinary");
            var pb = p.getAsJsonObject("heap_attrs").getAsJsonObject(id).getAsJsonArray("elementBinary");
            assertThat(mb).isEqualTo(pb);
            assertThat(mb.get(0)).isEqualTo(descriptor);
            assertThat(mb.get(1).isJsonNull()).isTrue();
        }
        assertThat(p.getAsJsonObject("heap_attrs").getAsJsonObject("3").get("binary")).isEqualTo(descriptor);
        var fieldBits = p.getAsJsonObject("heap_attrs").getAsJsonObject("1").getAsJsonArray("fieldBinary");
        assertThat(fieldBits).hasSize(3);
        assertThat(fieldBits.get(0)).isEqualTo(descriptor);
        assertThat(fieldBits.get(1).isJsonNull()).isTrue();
        assertThat(fieldBits.get(2).isJsonNull()).isTrue();
    }
}
