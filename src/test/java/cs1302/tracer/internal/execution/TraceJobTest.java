package cs1302.tracer.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.SourcePreparation;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import cs1302.tracer.internal.trace.BreakpointSpec;
import org.junit.jupiter.api.Test;

class TraceJobTest {
    @Test
    void callerProfilesKeepTheirSourcePolicies() {
        assertThat(job(TraceJob.Profile.JAVA, false).discovery())
                .isEqualTo(SourcePreparation.Discovery.SUBMITTED_OR_COMPILED);
        assertThat(job(TraceJob.Profile.CLI, false).discovery())
                .isEqualTo(SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED);
        assertThat(job(TraceJob.Profile.ENVELOPE, false).discovery())
                .isEqualTo(SourcePreparation.Discovery.NEIGHBORS);
        assertThat(job(TraceJob.Profile.NDJSON, false).discovery())
                .isEqualTo(SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED);
        assertThat(job(TraceJob.Profile.JAVA, true).discovery())
                .isEqualTo(SourcePreparation.Discovery.SUBMITTED_ONLY);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "JAVA, false, false, true, false, false, false",
        "JAVA, true, false, true, true, false, true",
        "JAVA, true, false, false, true, false, true",
        "NDJSON, true, false, false, true, false, true",
        "NDJSON, false, true, false, false, true, true",
        "CLI, false, false, true, true, false, false",
        "CLI, false, false, false, false, false, false",
        "CLI, true, false, true, true, false, true",
        "CLI, true, false, false, false, false, true",
        "ENVELOPE, false, false, true, false, true, false",
        "ENVELOPE, true, false, true, true, true, true",
        "ENVELOPE, true, false, false, false, true, true"
    })
    void omittedAndEmptySelectionPreserveEachCallerContract(TraceJob.Profile profile, boolean chronological,
            boolean accumulate, boolean omitted, boolean defaultSelection, boolean repeatedHits, boolean history) {
        TraceJob resolved = TraceJob.resolve(profile, TraceFormat.MODERN, TypeStyle.FQN, null,
                null, omitted ? null : List.of(), chronological, accumulate, false, false, false, false, true);
        cs1302.tracer.internal.trace.TraceDispatch.Selection selection = resolved.capture().selection();
        assertThat(selection.chronological()).isEqualTo(chronological);
        assertThat(selection.accumulate()).isEqualTo(repeatedHits);
        assertThat(resolved.capture().retainsHistory()).isEqualTo(history);
        if (defaultSelection) {
            assertThat(selection.breakpoints()).isNull();
        } else {
            assertThat(selection.breakpoints()).isEmpty();
        }
    }

    @Test
    void defaultsOverridesAndPresentationRemainTyped() {
        for (TraceJob.Profile profile : TraceJob.Profile.values()) {
            TraceLimits expected = profile == TraceJob.Profile.JAVA || profile == TraceJob.Profile.CLI
                    ? TraceLimits.instructorDefaults() : TraceLimits.unlimited();
            assertThat(job(profile, false).limits()).isEqualTo(expected);
        }
        TraceLimits limits = TraceLimits.unlimited();
        TraceJob resolved = TraceJob.resolve(TraceJob.Profile.JAVA, TraceFormat.MODERN, TypeStyle.FQN, limits,
                InspectionPolicy.FIELDS, List.of(), false, true, false, true, true, true, false);
        assertThat(resolved.limits()).isSameAs(limits);
        assertThat(resolved.inspection()).isEqualTo(InspectionPolicy.FIELDS);
        assertThat(resolved.discovery()).isEqualTo(SourcePreparation.Discovery.SUBMITTED_ONLY);
        assertThat(resolved.removeMainArgs()).isTrue();
        assertThat(resolved.inlineStrings()).isTrue();
        assertThat(resolved.removeMethodThis()).isTrue();
        assertThat(resolved.evalEnumHash()).isFalse();
        assertThat(job(TraceJob.Profile.JAVA, true).capture().chronological()).isTrue();
    }

    @Test
    void selectionIsDetachedButValidationRemainsDeferred() {
        List<String> raw = new ArrayList<>(Arrays.asList(null, " ", "12, , 13"));
        TraceJob resolved = TraceJob.resolve(TraceJob.Profile.NDJSON, TraceFormat.MODERN, TypeStyle.FQN, null,
                null, raw, false, false, false, false, false, false, true);
        raw.clear();
        assertThat(resolved.capture().selection().breakpoints())
                .containsExactly(BreakpointSpec.of(12), BreakpointSpec.of(13));
        TraceJob invalid = TraceJob.resolve(TraceJob.Profile.JAVA, TraceFormat.PYTUTOR, TypeStyle.FQN, null,
                null, List.of("bad"), false, false, true, false, false, false, true);
        assertThatThrownBy(() -> invalid.capture().selection()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invalid.validateThreadFormat(true))
                .hasMessage("--multithread requires --format modern");
        assertThatThrownBy(() -> invalid.validateThreadFormat(false))
                .hasMessage("multithread requires modern format");
        assertThatThrownBy(() -> TraceJob.validateCommandInspection(TraceJob.Profile.CLI, InspectionPolicy.FIELDS))
                .hasMessage("Inspection controls require --result-envelope");
        TraceJob.validateCommandInspection(TraceJob.Profile.ENVELOPE, InspectionPolicy.FIELDS);
        TraceJob.validateCommandInspection(TraceJob.Profile.CLI, InspectionPolicy.TRUSTED);
        job(TraceJob.Profile.JAVA, true).validateThreadFormat(false);
        job(TraceJob.Profile.JAVA, false).validateThreadFormat(false);
    }

    private static TraceJob job(TraceJob.Profile profile, boolean multithread) {
        return TraceJob.resolve(profile, TraceFormat.MODERN, TypeStyle.FQN, null, InspectionPolicy.TRUSTED,
                List.of(), false, false, multithread, false, false, false, true);
    }
}
