package cs1302.tracer.internal.execution;

import org.apiguardian.api.API;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.SourcePreparation;
import cs1302.tracer.internal.trace.BreakpointSpec;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;

/**
 * Effective tracing choices, independent of input transport and guest ownership.
 *
 * @param format Output format.
 * @param typeStyle Type presentation.
 * @param limits Effective budgets.
 * @param inspection Effective inspection.
 * @param discovery Permitted source evidence.
 * @param capture Capture intent, validated at the caller's existing lifecycle point.
 * @param multithread Whether application threads are captured.
 * @param removeMainArgs Whether main arguments are omitted.
 * @param inlineStrings Whether strings are inlined.
 * @param removeMethodThis Whether receivers are omitted.
 * @param evalEnumHash Whether enum hash evaluation is requested.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record TraceJob(TraceFormat format, TypeStyle typeStyle, TraceLimits limits, InspectionPolicy inspection,
        SourcePreparation.Discovery discovery, CaptureIntent capture, boolean multithread, boolean removeMainArgs,
        boolean inlineStrings, boolean removeMethodThis, boolean evalEnumHash) {

    /** Caller contracts whose defaults and selection conventions differ. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public enum Profile {
        /** Self-contained typed Java requests. */
        JAVA,
        /** Ordinary command output. */
        CLI,
        /** Command result envelopes. */
        ENVELOPE,
        /** Correlated batch requests. */
        NDJSON;

        /**
         * Supplies budgets when omitted by this caller.
         * @return Caller-specific defaults.
         */
        public TraceLimits defaultLimits() {
            return this == JAVA || this == CLI ? TraceLimits.instructorDefaults() : TraceLimits.unlimited();
        } // defaultLimits
    } // Profile

    /**
     * Resolves typed Java settings without converting through the wire protocol.
     * @param request Typed request.
     * @return Effective job choices.
     */
    public static TraceJob fromJava(TraceRequest request) {
        return resolve(Profile.JAVA, request.format(), request.typeStyle(), request.limits(), request.inspection(),
                request.breakpoints(), request.chronological(), request.accumulate(), request.multithread(),
                request.removeMainArgs(), request.inlineStrings(), request.removeMethodThis(), true);
    } // fromJava

    /**
     * Resolves caller policy without moving breakpoint validation ahead of source preparation.
     * @param profile Caller contract.
     * @param format Output format.
     * @param typeStyle Type presentation.
     * @param limits Supplied budgets, or null for caller defaults.
     * @param inspection Requested inspection, or null for trusted inspection.
     * @param breakpoints Raw selection; null preserves omission.
     * @param chronological Requested chronological capture.
     * @param accumulate Requested repeated hits.
     * @param multithread Requested application threads.
     * @param removeMainArgs Main argument presentation.
     * @param inlineStrings String presentation.
     * @param removeMethodThis Receiver presentation.
     * @param evalEnumHash Enum hash evaluation.
     * @return Effective job choices.
     */
    public static TraceJob resolve(Profile profile, TraceFormat format, TypeStyle typeStyle, TraceLimits limits,
            InspectionPolicy inspection, List<String> breakpoints, boolean chronological, boolean accumulate,
            boolean multithread, boolean removeMainArgs, boolean inlineStrings, boolean removeMethodThis,
            boolean evalEnumHash) {
        InspectionPolicy effective = multithread ? InspectionPolicy.FIELDS
                : inspection == null ? InspectionPolicy.TRUSTED : inspection;
        SourcePreparation.Discovery discovery = effective == InspectionPolicy.FIELDS
                ? SourcePreparation.Discovery.SUBMITTED_ONLY : switch (profile) {
        case JAVA -> SourcePreparation.Discovery.SUBMITTED_OR_COMPILED;
        case ENVELOPE -> SourcePreparation.Discovery.NEIGHBORS;
        case CLI, NDJSON -> SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED;
        }; // switch
        return new TraceJob(format, typeStyle, limits == null ? profile.defaultLimits() : limits, effective, discovery,
                new CaptureIntent(profile, breakpoints, chronological || multithread, accumulate), multithread,
                removeMainArgs, inlineStrings, removeMethodThis, evalEnumHash);
    } // resolve

    /**
     * Checks the thread/format interaction where the caller previously checked it.
     * @param cli Whether to use command-option spelling in diagnostics.
     */
    public void validateThreadFormat(boolean cli) {
        if (this.multithread && this.format != TraceFormat.MODERN) {
            throw new IllegalArgumentException(cli ? "--multithread requires --format modern"
                    : "multithread requires modern format");
        } // if
    } // validateThreadFormat

    /**
     * Checks explicit inspection controls after CLI breakpoint validation.
     * @param profile Command contract.
     * @param inspection Requested inspection before multithread implications.
     */
    public static void validateCommandInspection(Profile profile, InspectionPolicy inspection) {
        if (profile == Profile.CLI && inspection != InspectionPolicy.TRUSTED) {
            throw new IllegalArgumentException("Inspection controls require --result-envelope");
        } // if
    } // validateCommandInspection

    /**
     * Parses breakpoint text while retaining the existing ignored blank/null items.
     * @param raw Caller-supplied text, or null.
     * @return Parsed locations.
     */
    public static List<BreakpointSpec> parseBreakpoints(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        } // if
        List<BreakpointSpec> specs = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            } // if
            for (String part : item.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    specs.add(BreakpointSpec.parse(trimmed));
                } // if
            } // for
        } // for
        return List.copyOf(specs);
    } // parseBreakpoints

    /** Deferred capture selection; raw caller conventions never reach execution. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public static final class CaptureIntent {
        private final Profile profile;
        private final List<String> raw;
        private final boolean chronological;
        private final boolean accumulate;

        /**
         * Detaches caller selection without validating its text.
         * @param profile Caller contract.
         * @param raw Raw breakpoint text.
         * @param chronological Effective chronological selection.
         * @param accumulate Requested repeated hits.
         */
        private CaptureIntent(Profile profile, List<String> raw, boolean chronological, boolean accumulate) {
            this.profile = profile;
            this.raw = raw == null ? null : Collections.unmodifiableList(new ArrayList<>(raw));
            this.chronological = chronological;
            this.accumulate = accumulate;
        } // CaptureIntent

        /**
         * Whether capture preserves event order.
         * @return Effective chronological selection.
         */
        public boolean chronological() {
            return this.chronological;
        } // chronological

        /**
         * Whether the retained history accumulates states.
         * @return History policy.
         */
        public boolean retainsHistory() {
            return this.chronological || this.accumulate;
        } // retainsHistory

        /**
         * Resolves breakpoint text at the caller's established validation point.
         * @return Dispatch-ready selection with caller conventions resolved.
         */
        public TraceDispatch.Selection selection() {
            List<BreakpointSpec> specs = parseBreakpoints(this.raw);
            boolean all = this.chronological && (this.profile == Profile.JAVA || this.profile == Profile.NDJSON
                    ? specs.isEmpty() : this.raw == null);
            boolean finalState = !this.chronological && this.profile == Profile.CLI && this.raw == null;
            return TraceDispatch.Selection.select(this.chronological,
                    this.profile == Profile.ENVELOPE || this.accumulate,
                    all || finalState ? null : specs);
        } // selection
    } // CaptureIntent
} // TraceJob
