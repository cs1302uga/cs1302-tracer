package cs1302.tracer.internal.serialize;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import cs1302.tracer.internal.model.modern.HeapObject;
import cs1302.tracer.internal.model.modern.Variable;
import java.io.IOException;

/** Omits absent additive metadata even when envelopes preserve legacy null properties. */
final class BinaryMetadataAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != Variable.class && type.getRawType() != HeapObject.class) {
            return null;
        } // if
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
        TypeAdapter<JsonElement> json = gson.getAdapter(JsonElement.class);
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter writer, T value) throws IOException {
                JsonObject object = delegate.toJsonTree(value).getAsJsonObject();
                omitNull(object, "binary");
                omitNull(object, "elementBinary");
                json.write(writer, object);
            } // write

            @Override
            public T read(JsonReader reader) throws IOException {
                return delegate.read(reader);
            } // read
        }.nullSafe();
    } // create

    /**
     * Removes only absent binary descriptors, leaving established null properties untouched.
     * @param object Serialized record.
     * @param name Optional property.
     */
    private static void omitNull(JsonObject object, String name) {
        if (object.has(name) && object.get(name).isJsonNull()) {
            object.remove(name);
        } // if
    } // omitNull
} // BinaryMetadataAdapterFactory
