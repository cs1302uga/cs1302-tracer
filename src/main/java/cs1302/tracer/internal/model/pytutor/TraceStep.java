package cs1302.tracer.internal.model.pytutor;

import org.apiguardian.api.API;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * @param stdinConsumed Standard input consumed up to this step.
 * @param stdinOffset UTF-16 code-unit offset reached in standard input up to this step.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record TraceStep(@SerializedName("stdout") String stdout, @SerializedName("stderr") String stderr,
        @SerializedName("event") String event, @SerializedName("func_name") String funcName,
        @SerializedName("line") long line, @SerializedName("stack_to_render") List<RenderStackFrame> stackToRender,
        @SerializedName("globals") Map<String, Object> globals,
        @SerializedName("globals_attrs") Map<String, Object> globalsAttrs,
        @SerializedName("ordered_globals") List<String> orderedGlobals,
        @SerializedName("heap") Map<String, Object> heap, @SerializedName("heap_attrs") Map<String, Object> heapAttrs,
        @SerializedName("file") String file, @SerializedName("stdinConsumed") String stdinConsumed,
        @SerializedName("stdinOffset") int stdinOffset) {

    /**
     * Constructs a single-file trace step without a file path and defaulting stdin tracking.
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
    public TraceStep(String stdout, String stderr, String event, String funcName, long line,
            List<RenderStackFrame> stackToRender, Map<String, Object> globals, Map<String, Object> globalsAttrs,
            List<String> orderedGlobals, Map<String, Object> heap, Map<String, Object> heapAttrs) {
        this(stdout, stderr, event, funcName, line, stackToRender, globals, globalsAttrs, orderedGlobals, heap,
                heapAttrs, null, "", 0);
    } // TraceStep

    /**
     * Constructs a trace step with a file path and defaulting stdin tracking.
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
     * @param file Relative source file path.
     */
    public TraceStep(String stdout, String stderr, String event, String funcName, long line,
            List<RenderStackFrame> stackToRender, Map<String, Object> globals, Map<String, Object> globalsAttrs,
            List<String> orderedGlobals, Map<String, Object> heap, Map<String, Object> heapAttrs, String file) {
        this(stdout, stderr, event, funcName, line, stackToRender, globals, globalsAttrs, orderedGlobals, heap,
                heapAttrs, file, "", 0);
    } // TraceStep

    /**
     * Creates a builder with empty consumed input and zero input offset.
     * Other references are unset, and numeric fields default to zero.
     *
     * @return A new builder.
     * @since 4.1.0
     */
    public static Builder builder() {
        return new Builder();
    } // builder

    /**
     * Copies every value into a builder, detaching immediate collections.
     * Nested values remain shared; nulls and iteration order are preserved.
     *
     * @return An independent copy builder.
     * @since 4.1.0
     */
    public Builder toBuilder() {
        return builder()
                .stdout(this.stdout)
                .stderr(this.stderr)
                .event(this.event)
                .funcName(this.funcName)
                .line(this.line)
                .stackToRender(this.stackToRender)
                .globals(this.globals)
                .globalsAttrs(this.globalsAttrs)
                .orderedGlobals(this.orderedGlobals)
                .heap(this.heap)
                .heapAttrs(this.heapAttrs)
                .file(this.file)
                .stdinConsumed(this.stdinConsumed)
                .stdinOffset(this.stdinOffset);
    } // toBuilder

    /**
     * Mutable, reusable construction settings; not thread-safe.
     * Collection setters make unmodifiable shallow copies and preserve nulls and order.
     * Existing constructors retain their collection ownership behavior.
     * Trace rendering owns field relationships and format mapping.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public static final class Builder {

        private String stdout;
        private String stderr;
        private String event;
        private String funcName;
        private long line;
        private List<RenderStackFrame> stackToRender;
        private Map<String, Object> globals;
        private Map<String, Object> globalsAttrs;
        private List<String> orderedGlobals;
        private Map<String, Object> heap;
        private Map<String, Object> heapAttrs;
        private String file;
        private String stdinConsumed = "";
        private int stdinOffset;

        /** Creates default construction settings. */
        private Builder() {} // Builder

        /**
         * Sets captured standard output.
         *
         * @param stdout Captured standard output.
         * @return This builder.
         */
        public Builder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        } // stdout

        /**
         * Sets captured standard error.
         *
         * @param stderr Captured standard error.
         * @return This builder.
         */
        public Builder stderr(String stderr) {
            this.stderr = stderr;
            return this;
        } // stderr

        /**
         * Sets event kind supplied by trace rendering.
         *
         * @param event Event kind supplied by trace rendering.
         * @return This builder.
         */
        public Builder event(String event) {
            this.event = event;
            return this;
        } // event

        /**
         * Sets executing function name.
         *
         * @param funcName Executing function name.
         * @return This builder.
         */
        public Builder funcName(String funcName) {
            this.funcName = funcName;
            return this;
        } // funcName

        /**
         * Sets source line.
         *
         * @param line Source line.
         * @return This builder.
         */
        public Builder line(long line) {
            this.line = line;
            return this;
        } // line

        /**
         * Sets rendered call stack frames.
         *
         * @param stackToRender Rendered call stack frames.
         * @return This builder.
         */
        public Builder stackToRender(List<RenderStackFrame> stackToRender) {
            this.stackToRender = stackToRender == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(stackToRender));
            return this;
        } // stackToRender

        /**
         * Sets global values in iteration order.
         *
         * @param globals Global values in iteration order.
         * @return This builder.
         */
        public Builder globals(Map<String, Object> globals) {
            this.globals = globals == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(globals));
            return this;
        } // globals

        /**
         * Sets global type attributes in iteration order.
         *
         * @param globalsAttrs Global type attributes in iteration order.
         * @return This builder.
         */
        public Builder globalsAttrs(Map<String, Object> globalsAttrs) {
            this.globalsAttrs = globalsAttrs == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(globalsAttrs));
            return this;
        } // globalsAttrs

        /**
         * Sets ordered global names.
         *
         * @param orderedGlobals Ordered global names.
         * @return This builder.
         */
        public Builder orderedGlobals(List<String> orderedGlobals) {
            this.orderedGlobals = orderedGlobals == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(orderedGlobals));
            return this;
        } // orderedGlobals

        /**
         * Sets heap objects in iteration order.
         *
         * @param heap Heap objects in iteration order.
         * @return This builder.
         */
        public Builder heap(Map<String, Object> heap) {
            this.heap = heap == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(heap));
            return this;
        } // heap

        /**
         * Sets heap attributes in iteration order.
         *
         * @param heapAttrs Heap attributes in iteration order.
         * @return This builder.
         */
        public Builder heapAttrs(Map<String, Object> heapAttrs) {
            this.heapAttrs = heapAttrs == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(heapAttrs));
            return this;
        } // heapAttrs

        /**
         * Sets optional source file.
         *
         * @param file Optional source file.
         * @return This builder.
         */
        public Builder file(String file) {
            this.file = file;
            return this;
        } // file

        /**
         * Sets consumed input.
         *
         * @param stdinConsumed Consumed input.
         * @return This builder.
         */
        public Builder stdinConsumed(String stdinConsumed) {
            this.stdinConsumed = stdinConsumed;
            return this;
        } // stdinConsumed

        /**
         * Sets consumed input offset.
         *
         * @param stdinOffset Consumed input offset.
         * @return This builder.
         */
        public Builder stdinOffset(int stdinOffset) {
            this.stdinOffset = stdinOffset;
            return this;
        } // stdinOffset

        /**
         * Constructs a value without interpreting its supplied settings.
         *
         * @return The constructed value.
         */
        public TraceStep build() {
            return new TraceStep(this.stdout, this.stderr, this.event, this.funcName, this.line,
                    this.stackToRender, this.globals, this.globalsAttrs, this.orderedGlobals, this.heap,
                    this.heapAttrs, this.file, this.stdinConsumed, this.stdinOffset);
        } // build

    } // Builder

} // TraceStep
