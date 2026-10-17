package cs1302.tracer.internal.model.modern;

import org.apiguardian.api.API;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * @param stdinOffset UTF-16 code-unit offset reached in standard input up to this step.
 * @param threads Application threads, or null for legacy traces.
 * @param triggeringThreadId Event thread identity, or null for legacy traces.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record Step(@SerializedName("step") int step, @SerializedName("line") long line,
        @SerializedName("file") String file, @SerializedName("event") String event,
        @SerializedName("method") String method, @SerializedName("callStack") List<StackFrame> callStack,
        @SerializedName("statics") List<Variable> statics, @SerializedName("heap") Map<String, HeapObject> heap,
        @SerializedName("stdout") String stdout, @SerializedName("stderr") String stderr,
        @SerializedName("stdinConsumed") String stdinConsumed, @SerializedName("stdinOffset") int stdinOffset,
        @SerializedName("threads") List<ThreadState> threads,
        @SerializedName("triggeringThreadId") Long triggeringThreadId) {

    /**
     * A thread's stack and execution state at a trace step.
     *
     * @param id Thread identity within this trace.
     * @param name Thread name.
     * @param state Observed execution state.
     * @param callStack Application stack frames.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record ThreadState(long id, String name, String state, List<StackFrame> callStack) {} // ThreadState

    /**
     * Constructs a step without thread metadata.
     *
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
    public Step(int step, long line, String file, String event, String method, List<StackFrame> callStack,
            List<Variable> statics, Map<String, HeapObject> heap, String stdout, String stderr, String stdinConsumed,
            int stdinOffset) {
        this(step, line, file, event, method, callStack, statics, heap, stdout, stderr, stdinConsumed, stdinOffset,
                null, null);
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
    public Step(int step, long line, String file, String event, String method, List<StackFrame> callStack,
            List<Variable> statics, Map<String, HeapObject> heap, String stdout, String stderr) {
        this(step, line, file, event, method, callStack, statics, heap, stdout, stderr, "", 0);
    } // Step

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
                .step(this.step)
                .line(this.line)
                .file(this.file)
                .event(this.event)
                .method(this.method)
                .callStack(this.callStack)
                .statics(this.statics)
                .heap(this.heap)
                .stdout(this.stdout)
                .stderr(this.stderr)
                .stdinConsumed(this.stdinConsumed)
                .stdinOffset(this.stdinOffset)
                .threads(this.threads)
                .triggeringThreadId(this.triggeringThreadId);
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

        private int step;
        private long line;
        private String file;
        private String event;
        private String method;
        private List<StackFrame> callStack;
        private List<Variable> statics;
        private Map<String, HeapObject> heap;
        private String stdout;
        private String stderr;
        private String stdinConsumed = "";
        private int stdinOffset;
        private List<ThreadState> threads;
        private Long triggeringThreadId;

        /** Creates default construction settings. */
        private Builder() {} // Builder

        /**
         * Sets sequential step index.
         *
         * @param step Sequential step index.
         * @return This builder.
         */
        public Builder step(int step) {
            this.step = step;
            return this;
        } // step

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
         * Sets executing method.
         *
         * @param method Executing method.
         * @return This builder.
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        } // method

        /**
         * Sets call stack frames.
         *
         * @param callStack Call stack frames.
         * @return This builder.
         */
        public Builder callStack(List<StackFrame> callStack) {
            this.callStack = callStack == null ? null : Collections.unmodifiableList(new ArrayList<>(callStack));
            return this;
        } // callStack

        /**
         * Sets static variables.
         *
         * @param statics Static variables.
         * @return This builder.
         */
        public Builder statics(List<Variable> statics) {
            this.statics = statics == null ? null : Collections.unmodifiableList(new ArrayList<>(statics));
            return this;
        } // statics

        /**
         * Sets heap objects in iteration order.
         *
         * @param heap Heap objects in iteration order.
         * @return This builder.
         */
        public Builder heap(Map<String, HeapObject> heap) {
            this.heap = heap == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(heap));
            return this;
        } // heap

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
         * Sets optional application thread states.
         *
         * @param threads Optional application thread states.
         * @return This builder.
         */
        public Builder threads(List<ThreadState> threads) {
            this.threads = threads == null ? null : Collections.unmodifiableList(new ArrayList<>(threads));
            return this;
        } // threads

        /**
         * Sets optional event thread identity.
         *
         * @param triggeringThreadId Optional event thread identity.
         * @return This builder.
         */
        public Builder triggeringThreadId(Long triggeringThreadId) {
            this.triggeringThreadId = triggeringThreadId;
            return this;
        } // triggeringThreadId

        /**
         * Constructs a value without interpreting its supplied settings.
         *
         * @return The constructed value.
         */
        public Step build() {
            return new Step(this.step, this.line, this.file, this.event, this.method, this.callStack,
                    this.statics, this.heap, this.stdout, this.stderr, this.stdinConsumed, this.stdinOffset,
                    this.threads, this.triggeringThreadId);
        } // build

    } // Builder

} // Step
