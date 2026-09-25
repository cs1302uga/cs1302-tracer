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
 * @param stdinConsumed Standard input consumed up to this step.
 * @param stdinOffset Character offset reached in standard input up to this step.
 * @param threads Application threads, or null for legacy traces.
 * @param triggeringThreadId Event thread identity, or null for legacy traces.
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
        @SerializedName("stderr") String stderr,
        @SerializedName("stdinConsumed") String stdinConsumed,
        @SerializedName("stdinOffset") int stdinOffset,
        @SerializedName("threads") List<ThreadState> threads,
        @SerializedName("triggeringThreadId") Long triggeringThreadId) {

    /**
     * A thread's stack and execution state at a trace step.
     * @param id Thread identity within this trace.
     * @param name Thread name.
     * @param state Observed execution state.
     * @param callStack Application stack frames.
     */
    public record ThreadState(long id, String name, String state, List<StackFrame> callStack) {
    } // ThreadState

    /**
     * Constructs a step without thread metadata.
     * @param step Step number.
     * @param line Source line.
     * @param file Source file.
     * @param event Event kind.
     * @param method Executing method.
     * @param callStack Stack frames.
     * @param statics Static fields.
     * @param heap Heap objects.
     * @param stdout Standard output.
     * @param stderr Standard error.
     * @param stdinConsumed Consumed input.
     * @param stdinOffset Input offset.
     */
    public Step(int step, long line, String file, String event, String method,
            List<StackFrame> callStack, List<Variable> statics, Map<String, HeapObject> heap,
            String stdout, String stderr, String stdinConsumed, int stdinOffset) {
        this(step, line, file, event, method, callStack, statics, heap, stdout, stderr,
                stdinConsumed, stdinOffset, null, null);
    } // Step

    /**
     * Constructs a step defaulting stdin tracking attributes.
     *
     * @param step The sequential step index.
     * @param line The current line number.
     * @param file The relative source file path.
     * @param event The event type.
     * @param method The executing method name.
     * @param callStack The call stack frames.
     * @param statics Static variables.
     * @param heap Heap objects map.
     * @param stdout Captured standard output.
     * @param stderr Captured standard error.
     */
    public Step(
            int step,
            long line,
            String file,
            String event,
            String method,
            List<StackFrame> callStack,
            List<Variable> statics,
            Map<String, HeapObject> heap,
            String stdout,
            String stderr) {
        this(step, line, file, event, method, callStack, statics, heap, stdout, stderr, "", 0);
    } // Step

} // Step
