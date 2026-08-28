package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Represents a typed heap object in the modern trace format.
 *
 * @param id The unique reference ID of this heap object.
 * @param type The runtime or declared type of the object.
 * @param kind The category of heap object: "object", "array", "string", "lambda", or "box".
 * @param fields The list of instance fields for objects.
 * @param elements The list of element values for arrays.
 * @param value The scalar string or boxed value.
 * @param sam The single abstract method implementation for lambdas.
 */
public record HeapObject(
        @SerializedName("id") long id,
        @SerializedName("type") String type,
        @SerializedName("kind") String kind,
        @SerializedName("fields") List<Variable> fields,
        @SerializedName("elements") List<Object> elements,
        @SerializedName("value") Object value,
        @SerializedName("sam") String sam) {

    /**
     * Factory method for general instance objects.
     *
     * @param id The heap object ID.
     * @param type The object type.
     * @param fields The object fields.
     * @return A new HeapObject instance for an object.
     */
    public static HeapObject ofObject(long id, String type, List<Variable> fields) {
        return new HeapObject(id, type, "object", fields, null, null, null);
    } // ofObject

    /**
     * Factory method for arrays.
     *
     * @param id The heap object ID.
     * @param type The array component or array type.
     * @param elements The array elements.
     * @return A new HeapObject instance for an array.
     */
    public static HeapObject ofArray(long id, String type, List<Object> elements) {
        return new HeapObject(id, type, "array", null, elements, null, null);
    } // ofArray

    /**
     * Factory method for strings.
     *
     * @param id The heap object ID.
     * @param value The string value.
     * @return A new HeapObject instance for a string.
     */
    public static HeapObject ofString(long id, String value) {
        return new HeapObject(id, "java.lang.String", "string", null, null, value, null);
    } // ofString

    /**
     * Factory method for boxed primitives.
     *
     * @param id The heap object ID.
     * @param type The boxed type.
     * @param value The primitive value.
     * @return A new HeapObject instance for a box.
     */
    public static HeapObject ofBox(long id, String type, Object value) {
        return new HeapObject(id, type, "box", null, null, value, null);
    } // ofBox

    /**
     * Factory method for lambdas.
     *
     * @param id The heap object ID.
     * @param type The functional interface type.
     * @param sam The single abstract method implementation.
     * @return A new HeapObject instance for a lambda.
     */
    public static HeapObject ofLambda(long id, String type, String sam) {
        return new HeapObject(id, type, "lambda", null, null, null, sam);
    } // ofLambda
} // HeapObject
