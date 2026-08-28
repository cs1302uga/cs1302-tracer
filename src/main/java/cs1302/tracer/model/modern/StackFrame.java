package cs1302.tracer.model.modern;

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
public record StackFrame(
        @SerializedName("methodName") String methodName,
        @SerializedName("line") long line,
        @SerializedName("file") String file,
        @SerializedName("isHighlighted") boolean isHighlighted,
        @SerializedName("this") Reference thisObject,
        @SerializedName("locals") List<Variable> locals) {
} // StackFrame
