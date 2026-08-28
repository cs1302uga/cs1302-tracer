package cs1302.tracer.model.pytutor;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Represents a single execution step / memory snapshot in a PyTutor trace.
 *
 * @param stdout Standard output captured up to this point.
 * @param stderr Standard error captured up to this point.
 * @param event The trace event type (e.g. "step_line").
 * @param funcName The name of the executing function/method.
 * @param line The source line number where the snapshot was taken.
 * @param stackToRender The call stack frames to render.
 * @param globals The static/global variables.
 * @param globalsAttrs Type attributes of static/global variables.
 * @param orderedGlobals The ordered names of static/global variables.
 * @param heap The heap objects map.
 * @param heapAttrs Type and structural attributes of heap objects.
 * @param file The optional relative source file path for multi-file programs.
 */
public record TraceStep(
        @SerializedName("stdout") String stdout,
        @SerializedName("stderr") String stderr,
        @SerializedName("event") String event,
        @SerializedName("func_name") String funcName,
        @SerializedName("line") long line,
        @SerializedName("stack_to_render") List<RenderStackFrame> stackToRender,
        @SerializedName("globals") Map<String, Object> globals,
        @SerializedName("globals_attrs") Map<String, Object> globalsAttrs,
        @SerializedName("ordered_globals") List<String> orderedGlobals,
        @SerializedName("heap") Map<String, Object> heap,
        @SerializedName("heap_attrs") Map<String, Object> heapAttrs,
        @SerializedName("file") String file) {

    /**
     * Constructs a single-file trace step without a file path.
     *
     * @param stdout Captured standard output.
     * @param stderr Captured standard error.
     * @param event Event type string.
     * @param funcName Executing function name.
     * @param line Line number.
     * @param stackToRender Stack frames to render.
     * @param globals Global variables.
     * @param globalsAttrs Global variable attributes.
     * @param orderedGlobals Ordered global names.
     * @param heap Heap objects map.
     * @param heapAttrs Heap attributes.
     */
    public TraceStep(
            String stdout,
            String stderr,
            String event,
            String funcName,
            long line,
            List<RenderStackFrame> stackToRender,
            Map<String, Object> globals,
            Map<String, Object> globalsAttrs,
            List<String> orderedGlobals,
            Map<String, Object> heap,
            Map<String, Object> heapAttrs) {
        this(
                stdout,
                stderr,
                event,
                funcName,
                line,
                stackToRender,
                globals,
                globalsAttrs,
                orderedGlobals,
                heap,
                heapAttrs,
                null);
    } // TraceStep
} // TraceStep
