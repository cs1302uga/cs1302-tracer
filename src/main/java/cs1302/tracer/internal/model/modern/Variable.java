package cs1302.tracer.internal.model.modern;

import org.apiguardian.api.API;

import cs1302.tracer.internal.model.PrimitiveBinary;
import com.google.gson.annotations.SerializedName;

/**
 * Represents a declared variable or field in the modern trace format.
 *
 * @param name The variable or field identifier name.
 * @param type The declared Java type name.
 * @param value The value (primitive literal, {@link Reference}, or null).
 * @param binary Optional primitive binary metadata.
 * @param isFinal True if declared as final, false otherwise.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record Variable(@SerializedName("name") String name, @SerializedName("type") String type,
        @SerializedName("value") Object value, @SerializedName("final") boolean isFinal,
        @SerializedName("binary") PrimitiveBinary binary) {

    /**
     * Creates a variable without binary metadata.
     * @param name Identifier.
     * @param type Declared type.
     * @param value Serialized value.
     * @param isFinal Whether final.
     */
    public Variable(String name, String type, Object value, boolean isFinal) {
        this(name, type, value, isFinal, null);
    } // Variable
} // Variable
