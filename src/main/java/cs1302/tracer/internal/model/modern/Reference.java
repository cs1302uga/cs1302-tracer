package cs1302.tracer.internal.model.modern;

import org.apiguardian.api.API;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a pointer reference to a heap object in the modern trace format.
 *
 * @param ref The unique numerical ID of the referenced heap object.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record Reference(@SerializedName("ref") long ref) {} // Reference
