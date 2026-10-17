package cs1302.tracer.internal.batch;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import cs1302.tracer.api.TraceClassPath;
import java.lang.reflect.Type;
import java.nio.file.Path;
import org.apiguardian.api.API;

/**
 * A raw batch dependency: either a literal path or an explicit root and portable glob.
 * A JSON string abbreviates a literal path; object forms preserve unresolved malformed settings
 * until job validation so semantic errors retain their correlation identifier.
 *
 * @param path Literal filesystem path, or null for a glob selection.
 * @param root Glob search root, or null for a literal path.
 * @param glob Portable root-relative glob, or null for a literal path.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
@JsonAdapter(BatchClassPathEntry.Adapter.class)
public record BatchClassPathEntry(String path, String root, String glob) {

    /**
     * Adds the requested selection after checking its exclusive literal/glob shape.
     *
     * @param builder Destination for one ordered selection.
     * @throws IllegalArgumentException If the shape or path/pattern syntax is invalid.
     */
    public void addTo(TraceClassPath.Builder builder) {
        if (this.path != null && this.root == null && this.glob == null) {
            builder.addPath(Path.of(this.path));
            return;
        } // if
        if (this.path == null && this.root != null && this.glob != null) {
            builder.addGlob(Path.of(this.root), this.glob);
            return;
        } // if
        throw new IllegalArgumentException("Each classPath entry must be a literal path or {root, glob}");
    } // addTo

    /** Decodes the literal string shorthand and the explicit object representation. */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public static final class Adapter implements JsonDeserializer<BatchClassPathEntry> {

        /** Creates a stateless JSON adapter. */
        public Adapter() {} // Adapter

        @Override
        public BatchClassPathEntry deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                return new BatchClassPathEntry(json.getAsString(), null, null);
            } // if
            if (!json.isJsonObject()) {
                throw new JsonParseException("classPath entries must be strings or objects");
            } // if
            JsonObject object = json.getAsJsonObject();
            return new BatchClassPathEntry(string(object, "path"), string(object, "root"), string(object, "glob"));
        } // deserialize

        /**
         * Reads an omitted or nullable string without coercing JSON numbers and booleans.
         *
         * @param object Object whose field is being decoded.
         * @param name Field name.
         * @return String value, or null for omission/null.
         */
        private static String string(JsonObject object, String name) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull()) {
                return null;
            } // if
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("classPath " + name + " must be a string");
            } // if
            return value.getAsString();
        } // string

    } // Adapter

} // BatchClassPathEntry
