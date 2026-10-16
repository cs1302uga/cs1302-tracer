package cs1302.tracer.internal.model.modern;

import org.apiguardian.api.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Represents a stack frame in the modern trace format.
 *
 * @param methodName The name of the executing method.
 * @param line The current line number in this frame.
 * @param file The relative source file path (if multi-file).
 * @param isHighlighted True if this is the currently active/topmost frame.
 * @param thisObject The {@link Reference} pointing to {@code this} on the heap, or null.
 * @param locals The ordered list of local variables in this frame.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record StackFrame(@SerializedName("methodName") String methodName, @SerializedName("line") long line,
        @SerializedName("file") String file, @SerializedName("isHighlighted") boolean isHighlighted,
        @SerializedName("this") Reference thisObject, @SerializedName("locals") List<Variable> locals) {} // StackFrame
