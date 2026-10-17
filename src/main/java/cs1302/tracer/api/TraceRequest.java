package cs1302.tracer.api;

import org.apiguardian.api.API;

import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TypeStyle;
import java.util.List;
import java.util.Objects;

/**
 * A self-contained tracing request; multi-file sources use the documented source delimiters.
 * All reference components and breakpoint entries must be non-null. The breakpoint list is
 * copied in its supplied order and cannot be modified through this record.
 *
 * <p>Construction preserves requested settings without compiling source or parsing breakpoint text.
 * During tracing, multithread capture requires {@link TraceFormat#MODERN} and implies chronological
 * capture and {@link InspectionPolicy#FIELDS}, without changing this record. Breakpoint text is
 * parsed after successful source preparation. A chronological request captures selected locations,
 * or all executable lines when its breakpoint selection is empty.
 *
 * @param source Submitted Java source text.
 * @param stdin Guest standard input.
 * @param format Output format.
 * @param breakpoints Selected breakpoint specifications; empty selects main exit unless chronological capture is used.
 * @param chronological Whether to capture selected locations chronologically; an empty selection captures all lines.
 * @param accumulate Whether to retain repeated selected breakpoint hits.
 * @param multithread Whether to capture application threads in modern format.
 * @param removeMainArgs Whether to omit the main argument variable.
 * @param inlineStrings Whether to render strings inline.
 * @param removeMethodThis Whether to omit receiver variables.
 * @param typeStyle Type-label presentation.
 * @param limits Resource budgets.
 * @param inspection Guest inspection policy.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceRequest(String source, String stdin, TraceFormat format, List<String> breakpoints,
        boolean chronological, boolean accumulate, boolean multithread, boolean removeMainArgs,
        boolean inlineStrings, boolean removeMethodThis, TypeStyle typeStyle, TraceLimits limits,
        InspectionPolicy inspection) {

    /**
     * Validates required values and detaches breakpoint selection from the caller.
     *
     * @throws NullPointerException If any reference component or breakpoint entry is null.
     */
    public TraceRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(format, "format");
        breakpoints = List.copyOf(breakpoints);
        Objects.requireNonNull(typeStyle, "typeStyle");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(inspection, "inspection");
    } // TraceRequest

    /**
     * Creates a Python Tutor request with finite instructor budgets and trusted inspection.
     * Standard input and breakpoint selection are empty, every boolean setting is false,
     * and type labels use {@link TypeStyle#FQN}. The empty selection captures main exit.
     *
     * @param source Submitted Java source text.
     * @throws NullPointerException If source is null.
     */
    public TraceRequest(String source) {
        this(source, "", TraceFormat.PYTUTOR, List.of(), false, false, false, false, false, false,
                TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED);
    } // TraceRequest

    /**
     * Creates a builder with the same defaults as {@link #TraceRequest(String)}.
     * Effective tracing policy is resolved when the request is executed.
     *
     * @param source Submitted Java source text.
     * @return A mutable builder initialized with the source and default settings.
     * @throws NullPointerException If source is null.
     * @since 4.1.0
     */
    public static Builder builder(String source) {
        return new Builder(new TraceRequest(source));
    } // builder

    /**
     * Returns a builder initialized from this value.
     *
     * @return An independent mutable builder.
     * @since 4.1.0
     */
    public Builder toBuilder() {
        return new Builder(this);
    } // toBuilder

    /**
     * Mutable, reusable request construction; not thread-safe.
     * Breakpoint lists are detached when supplied. Required scalar values are checked by
     * {@link #build()}. Building preserves requested settings without parsing source or breakpoint text.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        private String source;
        private String stdin;
        private TraceFormat format;
        private List<String> breakpoints;
        private boolean chronological;
        private boolean accumulate;
        private boolean multithread;
        private boolean removeMainArgs;
        private boolean inlineStrings;
        private boolean removeMethodThis;
        private TypeStyle typeStyle;
        private TraceLimits limits;
        private InspectionPolicy inspection;

        /**
         * Copies every setting from an existing value.
         *
         * @param original Initial settings.
         */
        private Builder(TraceRequest original) {
            this.source = original.source();
            this.stdin = original.stdin();
            this.format = original.format();
            this.breakpoints = original.breakpoints();
            this.chronological = original.chronological();
            this.accumulate = original.accumulate();
            this.multithread = original.multithread();
            this.removeMainArgs = original.removeMainArgs();
            this.inlineStrings = original.inlineStrings();
            this.removeMethodThis = original.removeMethodThis();
            this.typeStyle = original.typeStyle();
            this.limits = original.limits();
            this.inspection = original.inspection();
        } // Builder

        /**
         * Sets the source setting.
         *
         * @param source Submitted Java source text.
         * @return This builder.
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        } // source

        /**
         * Sets the stdin setting.
         *
         * @param stdin Guest standard input.
         * @return This builder.
         */
        public Builder stdin(String stdin) {
            this.stdin = stdin;
            return this;
        } // stdin

        /**
         * Sets the format setting.
         *
         * @param format Output format.
         * @return This builder.
         */
        public Builder format(TraceFormat format) {
            this.format = format;
            return this;
        } // format

        /**
         * Sets and detaches the selected breakpoint specifications.
         *
         * @param breakpoints Selected breakpoint specifications; empty selects main exit unless chronological
         *     capture is used.
         * @return This builder.
         * @throws NullPointerException If the list or one of its elements is null.
         */
        public Builder breakpoints(List<String> breakpoints) {
            this.breakpoints = List.copyOf(breakpoints);
            return this;
        } // breakpoints

        /**
         * Sets the chronological setting.
         *
         * @param chronological Whether to capture selected locations chronologically; empty selects all lines.
         * @return This builder.
         */
        public Builder chronological(boolean chronological) {
            this.chronological = chronological;
            return this;
        } // chronological

        /**
         * Sets the accumulate setting.
         *
         * @param accumulate Whether to retain repeated selected breakpoint hits.
         * @return This builder.
         */
        public Builder accumulate(boolean accumulate) {
            this.accumulate = accumulate;
            return this;
        } // accumulate

        /**
         * Sets the multithread setting.
         *
         * @param multithread Whether to capture application threads; execution requires modern format and uses
         *     chronological capture with field-only inspection.
         * @return This builder.
         */
        public Builder multithread(boolean multithread) {
            this.multithread = multithread;
            return this;
        } // multithread

        /**
         * Sets the removeMainArgs setting.
         *
         * @param removeMainArgs Whether to omit the main argument variable.
         * @return This builder.
         */
        public Builder removeMainArgs(boolean removeMainArgs) {
            this.removeMainArgs = removeMainArgs;
            return this;
        } // removeMainArgs

        /**
         * Sets the inlineStrings setting.
         *
         * @param inlineStrings Whether to render strings inline.
         * @return This builder.
         */
        public Builder inlineStrings(boolean inlineStrings) {
            this.inlineStrings = inlineStrings;
            return this;
        } // inlineStrings

        /**
         * Sets the removeMethodThis setting.
         *
         * @param removeMethodThis Whether to omit receiver variables.
         * @return This builder.
         */
        public Builder removeMethodThis(boolean removeMethodThis) {
            this.removeMethodThis = removeMethodThis;
            return this;
        } // removeMethodThis

        /**
         * Sets the typeStyle setting.
         *
         * @param typeStyle Type-label presentation.
         * @return This builder.
         */
        public Builder typeStyle(TypeStyle typeStyle) {
            this.typeStyle = typeStyle;
            return this;
        } // typeStyle

        /**
         * Sets the limits setting.
         *
         * @param limits Resource budgets.
         * @return This builder.
         */
        public Builder limits(TraceLimits limits) {
            this.limits = limits;
            return this;
        } // limits

        /**
         * Sets the inspection setting.
         *
         * @param inspection Guest inspection policy.
         * @return This builder.
         */
        public Builder inspection(InspectionPolicy inspection) {
            this.inspection = inspection;
            return this;
        } // inspection

        /**
         * Builds a request using the existing constructor's validation.
         *
         * @return A new request.
         * @throws NullPointerException If a required value is null.
         */
        public TraceRequest build() {
            return new TraceRequest(this.source, this.stdin, this.format, this.breakpoints, this.chronological,
                    this.accumulate, this.multithread, this.removeMainArgs, this.inlineStrings,
                    this.removeMethodThis, this.typeStyle, this.limits, this.inspection);
        } // build

    } // Builder

} // TraceRequest
