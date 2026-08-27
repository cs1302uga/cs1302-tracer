package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a pointer reference to a heap object in the modern trace format.
 *
 * @param ref The unique numerical ID of the referenced heap object.
 */
public record Reference(@SerializedName("ref") long ref) {}
