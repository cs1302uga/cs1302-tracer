package cs1302.tracer.internal.batch;

import org.apiguardian.api.API;

import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TypeStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single batch tracing job request parsed from an NDJSON line.
 * Nullable settings preserve omission until the batch adapter resolves them; the builder does not fill defaults.
 *
 * @param id Optional job correlation identifier; uniqueness is caller-managed.
 * @param source Java source code (single or multi-file delimited).
 * @param format Output format (pytutor or modern).
 * @param stdin Optional guest standard input string.
 * @param breakpoints Optional list of breakpoint strings.
 * @param allBreakpoints True for chronological capture; omitted or empty selections use all valid lines.
 * @param accumulateBreakpoints True to retain all hits per breakpoint.
 * @param removeMainArgs True to omit main method args.
 * @param inlineStrings True to inline String fields.
 * @param removeMethodThis True to omit this reference in methods.
 * @param typeStyle Type styling (fqn or simple).
 * @param limits Optional resource limits.
 * @param inspection Optional inspection policy.
 * @param multithread Enable chronological platform-thread capture in modern format.
 * @param includePrimitiveBits Optional primitive binary metadata setting; omission resolves to false.
 * @param classPath Optional ordered binary dependency selections; omission resolves to no entries.
 * @param copyClassPath Optional dependency copying setting; omission resolves to false.
 * @param followClassPathLinks Optional link-following setting; omission resolves to false.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record BatchJobRequest(String id, String source, String format, String stdin, List<String> breakpoints,
        Boolean allBreakpoints, Boolean accumulateBreakpoints, Boolean removeMainArgs, Boolean inlineStrings,
        Boolean removeMethodThis, String typeStyle, TraceLimits limits, InspectionPolicy inspection,
        Boolean multithread, Boolean includePrimitiveBits, List<BatchClassPathEntry> classPath,
        Boolean copyClassPath, Boolean followClassPathLinks) {

    /**
     * Creates a request using the existing contract without binary dependency configuration.
     * @param id Optional job correlation identifier.
     * @param source Java source code.
     * @param format Output format.
     * @param stdin Optional standard input.
     * @param breakpoints Optional breakpoint selection.
     * @param allBreakpoints Chronological capture.
     * @param accumulateBreakpoints Retain breakpoint hits.
     * @param removeMainArgs Omit main arguments.
     * @param inlineStrings Inline strings.
     * @param removeMethodThis Omit receivers.
     * @param typeStyle Type styling.
     * @param limits Optional resource limits.
     * @param inspection Optional inspection policy.
     * @param multithread Enable platform-thread capture.
     * @param includePrimitiveBits Include primitive binary metadata.
     */
    public BatchJobRequest(String id, String source, String format, String stdin, List<String> breakpoints,
            Boolean allBreakpoints, Boolean accumulateBreakpoints, Boolean removeMainArgs, Boolean inlineStrings,
            Boolean removeMethodThis, String typeStyle, TraceLimits limits, InspectionPolicy inspection,
            Boolean multithread, Boolean includePrimitiveBits) {
        this(id, source, format, stdin, breakpoints, allBreakpoints, accumulateBreakpoints, removeMainArgs,
                inlineStrings, removeMethodThis, typeStyle, limits, inspection, multithread, includePrimitiveBits,
                null, null, null);
    } // BatchJobRequest

    /**
     * Creates a request with binary metadata disabled.
     * @param id Optional job correlation identifier; uniqueness is caller-managed.
     * @param source Java source code (single or multi-file delimited).
     * @param format Output format (pytutor or modern).
     * @param stdin Optional guest standard input string.
     * @param breakpoints Optional list of breakpoint strings.
     * @param allBreakpoints True for chronological capture; omitted or empty selections use all valid lines.
     * @param accumulateBreakpoints True to retain all hits per breakpoint.
     * @param removeMainArgs True to omit main method args.
     * @param inlineStrings True to inline String fields.
     * @param removeMethodThis True to omit this reference in methods.
     * @param typeStyle Type styling (fqn or simple).
     * @param limits Optional resource limits.
     * @param inspection Optional inspection policy.
     * @param multithread Enable chronological platform-thread capture in modern format.
     */
    public BatchJobRequest(String id, String source, String format, String stdin, List<String> breakpoints,
            Boolean allBreakpoints, Boolean accumulateBreakpoints, Boolean removeMainArgs, Boolean inlineStrings,
            Boolean removeMethodThis, String typeStyle, TraceLimits limits, InspectionPolicy inspection,
            Boolean multithread) {
        this(id, source, format, stdin, breakpoints, allBreakpoints, accumulateBreakpoints, removeMainArgs,
                inlineStrings, removeMethodThis, typeStyle, limits, inspection, multithread, false);
    } // BatchJobRequest

    /**
     * Constructs a request using the original single-stack contract.
     *
     * @param id Correlation ID.
     * @param source Source text.
     * @param format Trace format.
     * @param stdin Standard input.
     * @param breakpoints Breakpoint specifications.
     * @param allBreakpoints Chronological capture.
     * @param accumulateBreakpoints Retain breakpoint hits.
     * @param removeMainArgs Omit main arguments.
     * @param inlineStrings Inline strings.
     * @param removeMethodThis Omit this references.
     * @param typeStyle Type styling.
     * @param limits Job limits.
     * @param inspection Inspection policy.
     */
    public BatchJobRequest(String id, String source, String format, String stdin, List<String> breakpoints,
            Boolean allBreakpoints, Boolean accumulateBreakpoints, Boolean removeMainArgs, Boolean inlineStrings,
            Boolean removeMethodThis, String typeStyle, TraceLimits limits, InspectionPolicy inspection) {
        this(id, source, format, stdin, breakpoints, allBreakpoints, accumulateBreakpoints, removeMainArgs,
                inlineStrings, removeMethodThis, typeStyle, limits, inspection, false);
    } // BatchJobRequest

    /**
     * Resolves the trace format, defaulting to PYTUTOR if not specified.
     *
     * @return Resolved TraceFormat.
     * @throws IllegalArgumentException If format is unsupported.
     */
    public TraceFormat resolveFormat() {
        if (format == null || format.isBlank()) {
            return TraceFormat.PYTUTOR;
        } // if
        if (format.equalsIgnoreCase("modern")) {
            return TraceFormat.MODERN;
        } // if
        if (format.equalsIgnoreCase("pytutor")) {
            return TraceFormat.PYTUTOR;
        } // if
        throw new IllegalArgumentException("Unsupported trace format: " + format);
    } // resolveFormat

    /**
     * Resolves the type style, defaulting to FQN if not specified.
     *
     * @return Resolved TypeStyle.
     * @throws IllegalArgumentException If typeStyle is unsupported.
     */
    public TypeStyle resolveTypeStyle() {
        if (typeStyle == null || typeStyle.isBlank()) {
            return TypeStyle.FQN;
        } // if
        if (typeStyle.equalsIgnoreCase("simple")) {
            return TypeStyle.SIMPLE;
        } // if
        if (typeStyle.equalsIgnoreCase("fqn")) {
            return TypeStyle.FQN;
        } // if
        throw new IllegalArgumentException("Unsupported type style: " + typeStyle);
    } // resolveTypeStyle

    /**
     * Resolves the effective TraceLimits, defaulting to unlimited if omitted.
     *
     * @return Resolved TraceLimits.
     */
    public TraceLimits resolveLimits() {
        return limits != null ? limits : cs1302.tracer.internal.execution.TraceJob.Profile.NDJSON.defaultLimits();
    } // resolveLimits

    /**
     * Resolves per-job dependency syntax without selecting or copying files.
     * Strings are literal paths; neither native path-list splitting nor implicit glob detection applies.
     *
     * @return Immutable dependency configuration.
     * @throws IllegalArgumentException If a selection is null or malformed.
     */
    public TraceClassPath resolveClassPath() {
        TraceClassPath.Builder builder = TraceClassPath.builder().copy(Boolean.TRUE.equals(this.copyClassPath))
                .followLinks(Boolean.TRUE.equals(this.followClassPathLinks));
        if (this.classPath != null) {
            for (BatchClassPathEntry entry : this.classPath) {
                if (entry == null) {
                    throw new IllegalArgumentException("classPath entries cannot be null");
                } // if
                entry.addTo(builder);
            } // for
        } // if
        return builder.build();
    } // resolveClassPath

    /**
     * Creates a builder with every raw setting omitted.
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
                .id(this.id)
                .source(this.source)
                .format(this.format)
                .stdin(this.stdin)
                .breakpoints(this.breakpoints)
                .allBreakpoints(this.allBreakpoints)
                .accumulateBreakpoints(this.accumulateBreakpoints)
                .removeMainArgs(this.removeMainArgs)
                .inlineStrings(this.inlineStrings)
                .removeMethodThis(this.removeMethodThis)
                .typeStyle(this.typeStyle)
                .limits(this.limits)
                .inspection(this.inspection)
                .multithread(this.multithread)
                .includePrimitiveBits(this.includePrimitiveBits)
                .classPath(this.classPath)
                .copyClassPath(this.copyClassPath)
                .followClassPathLinks(this.followClassPathLinks);
    } // toBuilder

    /**
     * Mutable, reusable construction settings; not thread-safe.
     * Collection setters make unmodifiable shallow copies and preserve nulls and order.
     * Existing constructors retain their collection ownership behavior.
     * Omitted settings stay null; resolution and validation remain with their current owners.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public static final class Builder {

        private String id;
        private String source;
        private String format;
        private String stdin;
        private List<String> breakpoints;
        private Boolean allBreakpoints;
        private Boolean accumulateBreakpoints;
        private Boolean removeMainArgs;
        private Boolean inlineStrings;
        private Boolean removeMethodThis;
        private String typeStyle;
        private TraceLimits limits;
        private InspectionPolicy inspection;
        private Boolean multithread;
        private Boolean includePrimitiveBits;
        private List<BatchClassPathEntry> classPath;
        private Boolean copyClassPath;
        private Boolean followClassPathLinks;

        /** Creates default construction settings. */
        private Builder() {} // Builder

        /**
         * Sets job correlation identifier.
         *
         * @param id Job correlation identifier.
         * @return This builder.
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        } // id

        /**
         * Sets submitted source text.
         *
         * @param source Submitted source text.
         * @return This builder.
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        } // source

        /**
         * Sets unresolved format string.
         *
         * @param format Unresolved format string.
         * @return This builder.
         */
        public Builder format(String format) {
            this.format = format;
            return this;
        } // format

        /**
         * Sets optional guest standard input.
         *
         * @param stdin Optional guest standard input.
         * @return This builder.
         */
        public Builder stdin(String stdin) {
            this.stdin = stdin;
            return this;
        } // stdin

        /**
         * Sets optional breakpoint selection, including accepted null entries.
         *
         * @param breakpoints Optional breakpoint selection, including accepted null entries.
         * @return This builder.
         */
        public Builder breakpoints(List<String> breakpoints) {
            this.breakpoints = breakpoints == null ? null : Collections.unmodifiableList(new ArrayList<>(breakpoints));
            return this;
        } // breakpoints

        /**
         * Sets optional chronological capture setting.
         *
         * @param allBreakpoints Optional chronological capture setting.
         * @return This builder.
         */
        public Builder allBreakpoints(Boolean allBreakpoints) {
            this.allBreakpoints = allBreakpoints;
            return this;
        } // allBreakpoints

        /**
         * Sets optional retained-hit setting.
         *
         * @param accumulateBreakpoints Optional retained-hit setting.
         * @return This builder.
         */
        public Builder accumulateBreakpoints(Boolean accumulateBreakpoints) {
            this.accumulateBreakpoints = accumulateBreakpoints;
            return this;
        } // accumulateBreakpoints

        /**
         * Sets optional main-argument removal setting.
         *
         * @param removeMainArgs Optional main-argument removal setting.
         * @return This builder.
         */
        public Builder removeMainArgs(Boolean removeMainArgs) {
            this.removeMainArgs = removeMainArgs;
            return this;
        } // removeMainArgs

        /**
         * Sets optional string-inlining setting.
         *
         * @param inlineStrings Optional string-inlining setting.
         * @return This builder.
         */
        public Builder inlineStrings(Boolean inlineStrings) {
            this.inlineStrings = inlineStrings;
            return this;
        } // inlineStrings

        /**
         * Sets optional receiver-removal setting.
         *
         * @param removeMethodThis Optional receiver-removal setting.
         * @return This builder.
         */
        public Builder removeMethodThis(Boolean removeMethodThis) {
            this.removeMethodThis = removeMethodThis;
            return this;
        } // removeMethodThis

        /**
         * Sets unresolved type-style string.
         *
         * @param typeStyle Unresolved type-style string.
         * @return This builder.
         */
        public Builder typeStyle(String typeStyle) {
            this.typeStyle = typeStyle;
            return this;
        } // typeStyle

        /**
         * Sets optional requested limits.
         *
         * @param limits Optional requested limits.
         * @return This builder.
         */
        public Builder limits(TraceLimits limits) {
            this.limits = limits;
            return this;
        } // limits

        /**
         * Sets optional inspection policy.
         *
         * @param inspection Optional inspection policy.
         * @return This builder.
         */
        public Builder inspection(InspectionPolicy inspection) {
            this.inspection = inspection;
            return this;
        } // inspection

        /**
         * Sets optional platform-thread capture setting.
         *
         * @param multithread Optional platform-thread capture setting.
         * @return This builder.
         */
        public Builder multithread(Boolean multithread) {
            this.multithread = multithread;
            return this;
        } // multithread

        /**
         * Sets optional primitive binary metadata setting.
         *
         * @param includePrimitiveBits Optional primitive binary metadata setting.
         * @return This builder.
         */
        public Builder includePrimitiveBits(Boolean includePrimitiveBits) {
            this.includePrimitiveBits = includePrimitiveBits;
            return this;
        } // includePrimitiveBits

        /**
         * Sets optional binary selections, detaching the list while preserving omission and null entries.
         *
         * @param classPath Ordered raw selections, or null.
         * @return This builder.
         */
        public Builder classPath(List<BatchClassPathEntry> classPath) {
            this.classPath = classPath == null ? null : Collections.unmodifiableList(new ArrayList<>(classPath));
            return this;
        } // classPath

        /**
         * Sets optional copying for dependencies prepared afresh for each job.
         *
         * @param copyClassPath Copy dependencies, or null for omission.
         * @return This builder.
         */
        public Builder copyClassPath(Boolean copyClassPath) {
            this.copyClassPath = copyClassPath;
            return this;
        } // copyClassPath

        /**
         * Sets optional link following during dependency selection and copying.
         *
         * @param followClassPathLinks Follow encountered links, or null for omission.
         * @return This builder.
         */
        public Builder followClassPathLinks(Boolean followClassPathLinks) {
            this.followClassPathLinks = followClassPathLinks;
            return this;
        } // followClassPathLinks

        /**
         * Constructs a value without interpreting its supplied settings.
         *
         * @return The constructed value.
         */
        public BatchJobRequest build() {
            return new BatchJobRequest(this.id, this.source, this.format, this.stdin, this.breakpoints,
                    this.allBreakpoints, this.accumulateBreakpoints, this.removeMainArgs, this.inlineStrings,
                    this.removeMethodThis, this.typeStyle, this.limits, this.inspection, this.multithread,
                    this.includePrimitiveBits, this.classPath, this.copyClassPath, this.followClassPathLinks);
        } // build

    } // Builder

} // BatchJobRequest
