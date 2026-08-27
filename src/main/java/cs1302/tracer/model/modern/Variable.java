package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a declared variable or field in the modern trace format.
 *
 * @param name The variable or field identifier name.
 * @param type The declared Java type name.
 * @param value The value (primitive literal, {@link Reference}, or null).
 * @param isFinal True if declared as final, false otherwise.
 */
public record Variable(
    @SerializedName("name") String name,
    @SerializedName("type") String type,
    @SerializedName("value") Object value,
    @SerializedName("final") boolean isFinal) {}
