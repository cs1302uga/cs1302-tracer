package cs1302.tracer.internal.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.internal.model.PrimitiveBinary;
import cs1302.tracer.internal.model.modern.HeapObject;
import cs1302.tracer.internal.model.modern.Variable;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinaryMetadataAdapterFactoryTest {
    @Test
    void onlyNewAbsentMetadataIsOmittedInNullPreservingEnvelopes() {
        Gson gson = PyTutorSerializer.getGson();
        HeapObject heap = HeapObject.ofString(1L, "text");
        JsonObject encoded = JsonParser.parseString(gson.toJson(heap)).getAsJsonObject();
        assertThat(encoded.has("binary")).isFalse();
        assertThat(encoded.has("elementBinary")).isFalse();
        assertThat(encoded.get("fields").isJsonNull()).isTrue();
        assertThat(encoded.get("elements").isJsonNull()).isTrue();
        assertThat(gson.fromJson(encoded, HeapObject.class)).isEqualTo(heap);
        Variable variable = new Variable("x", "byte", null, false);
        JsonObject local = JsonParser.parseString(gson.toJson(variable)).getAsJsonObject();
        assertThat(local.has("binary")).isFalse();
        assertThat(local.get("value").isJsonNull()).isTrue();
        assertThat(gson.fromJson(local, Variable.class)).isEqualTo(variable);
        PrimitiveBinary bits = new PrimitiveBinary("byte", "twos-complement", "11111111");
        Variable annotated = new Variable("x", "byte", null, false, bits);
        assertThat(gson.fromJson(gson.toJson(annotated), Variable.class)).isEqualTo(annotated);
        HeapObject array = HeapObject.ofArray(2L, "byte[]", List.of()).withBinary(null, List.of(bits));
        assertThat(gson.fromJson(gson.toJson(array), HeapObject.class)).isEqualTo(array);
        assertThat(gson.toJson(null, Variable.class)).isEqualTo("null");
        assertThat(gson.fromJson("null", HeapObject.class)).isNull();
    }
}
