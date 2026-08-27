package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Represents a single execution step in the modern trace format.
 *
 * @param step The sequential step index (1-based).
 * @param line The current line number.
 * @param file The relative source file path (if multi-file).
 * @param event The event type (e.g. "step_line").
 * @param method The currently executing method name.
 * @param callStack The call stack frames (bottommost frame at index 0).
 * @param statics The loaded static/global variables.
 * @param heap The heap objects map keyed by unique reference ID.
 * @param stdout Standard output captured up to this step.
 * @param stderr Standard error captured up to this step.
 */
public record Step(
    @SerializedName("step") int step,
    @SerializedName("line") long line,
    @SerializedName("file") String file,
    @SerializedName("event") String event,
    @SerializedName("method") String method,
    @SerializedName("callStack") List<StackFrame> callStack,
    @SerializedName("statics") List<Variable> statics,
    @SerializedName("heap") Map<String, HeapObject> heap,
    @SerializedName("stdout") String stdout,
    @SerializedName("stderr") String stderr) {}
