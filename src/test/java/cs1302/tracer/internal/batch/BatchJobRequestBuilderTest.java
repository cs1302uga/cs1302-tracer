package cs1302.tracer.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TypeStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Raw batch settings preserve omission and caller-owned breakpoint containers. */
class BatchJobRequestBuilderTest {

    @Test
    void defaultsLeaveEveryRawSettingOmitted() {
        BatchJobRequest request = BatchJobRequest.builder().build();
        assertThat(request).isEqualTo(new BatchJobRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        assertThat(request.toBuilder().build()).isEqualTo(request);
        assertThat(request.resolveFormat()).isEqualTo(TraceFormat.PYTUTOR);
        assertThat(request.resolveTypeStyle()).isEqualTo(TypeStyle.FQN);
        assertThat(request.resolveLimits()).isEqualTo(TraceLimits.unlimited());
        assertThat(new Gson().toJson(request)).isEqualTo("{}");
        assertThat(new Gson().fromJson("{}", BatchJobRequest.class)).isEqualTo(request);
    }

    @Test
    void allSettingsMatchCanonicalConstructionAndCopyExactly() {
        TraceLimits limits = TraceLimits.instructorDefaults();
        BatchJobRequest expected = new BatchJobRequest("job", "class Main {}", "modern", "input", List.of("9"),
                true, true, true, true, true, "simple", limits, InspectionPolicy.FIELDS, true, true);
        BatchJobRequest actual = BatchJobRequest.builder().id("job").source("class Main {}").format("modern")
                .stdin("input").breakpoints(List.of("9")).allBreakpoints(true).accumulateBreakpoints(true)
                .removeMainArgs(true).inlineStrings(true).removeMethodThis(true).typeStyle("simple")
                .limits(limits).inspection(InspectionPolicy.FIELDS).multithread(true).includePrimitiveBits(true)
                .build();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.toBuilder().build()).isEqualTo(actual);
        Gson gson = new Gson();
        assertThat(gson.toJson(actual)).isEqualTo(gson.toJson(expected));
        assertThat(gson.fromJson(gson.toJson(actual), BatchJobRequest.class)).isEqualTo(actual);
    }

    @Test
    void explicitFalseAndEmptySelectionsSurviveJsonRoundTrip() {
        BatchJobRequest omitted = BatchJobRequest.builder().id("job").build();
        BatchJobRequest explicit = omitted.toBuilder().breakpoints(List.of()).allBreakpoints(false)
                .accumulateBreakpoints(false).removeMainArgs(false).inlineStrings(false).removeMethodThis(false)
                .multithread(false).includePrimitiveBits(false).build();
        Gson gson = new Gson();
        JsonObject omittedJson = gson.toJsonTree(omitted).getAsJsonObject();
        JsonObject explicitJson = gson.toJsonTree(explicit).getAsJsonObject();
        for (String field : List.of("allBreakpoints", "accumulateBreakpoints", "removeMainArgs", "inlineStrings",
                "removeMethodThis", "multithread", "includePrimitiveBits")) {
            assertThat(omittedJson.has(field)).isFalse();
            assertThat(explicitJson.get(field).getAsBoolean()).isFalse();
        }
        assertThat(omittedJson.has("breakpoints")).isFalse();
        assertThat(explicitJson.getAsJsonArray("breakpoints")).isEmpty();
        assertThat(gson.fromJson(gson.toJson(explicit), BatchJobRequest.class)).isEqualTo(explicit);
        assertThat(explicit.toBuilder().build()).isEqualTo(explicit);
    }

    @Test
    void breakpointSetterDetachesImmediatelyWithoutRejectingNullEntries() {
        List<String> breakpoints = new ArrayList<>(Arrays.asList("Second.java:8", null, "First.java:3"));
        BatchJobRequest.Builder builder = BatchJobRequest.builder().breakpoints(breakpoints);
        breakpoints.clear();
        BatchJobRequest first = builder.build();
        assertThat(first.breakpoints()).containsExactly("Second.java:8", null, "First.java:3");
        assertThatThrownBy(() -> first.breakpoints().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(builder.breakpoints(null).build().breakpoints()).isNull();
        assertThat(first.breakpoints()).hasSize(3);
        Gson gson = new Gson();
        assertThat(gson.fromJson(gson.toJson(first), BatchJobRequest.class)).isEqualTo(first);
    }

    @Test
    void copyBuilderDetachesButDoesNotChangeExistingConstructorOwnership() {
        List<String> breakpoints = new ArrayList<>(List.of("5"));
        BatchJobRequest original = new BatchJobRequest("job", "source", null, null, breakpoints,
                null, null, null, null, null, null, null, null, null, null);
        BatchJobRequest.Builder copy = original.toBuilder();
        breakpoints.add("7");
        BatchJobRequest copied = copy.build();
        assertThat(original.breakpoints()).containsExactly("5", "7");
        assertThat(copied.breakpoints()).containsExactly("5");
        assertThat(copy.id("next").build().id()).isEqualTo("next");
        assertThat(copied.id()).isEqualTo("job");
        assertThat(copied.toBuilder().id(null).source(null).format(null).stdin(null).breakpoints(null)
                .allBreakpoints(null).accumulateBreakpoints(null).removeMainArgs(null).inlineStrings(null)
                .removeMethodThis(null).typeStyle(null).limits(null).inspection(null).multithread(null)
                .includePrimitiveBits(null).build()).isEqualTo(BatchJobRequest.builder().build());
    }

    @Test
    void constructionPreservesUnresolvedAndMalformedSettings() {
        BatchJobRequest request = BatchJobRequest.builder().source("not Java").format("unsupported")
                .typeStyle("unsupported").breakpoints(List.of("not a breakpoint"))
                .inspection(InspectionPolicy.TRUSTED).multithread(true).build();
        assertThat(request.source()).isEqualTo("not Java");
        assertThat(request.breakpoints()).containsExactly("not a breakpoint");
        assertThat(request.inspection()).isEqualTo(InspectionPolicy.TRUSTED);
        assertThat(request.multithread()).isTrue();
        assertThat(request.toBuilder().build()).isEqualTo(request);
        assertThatThrownBy(request::resolveFormat).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(request::resolveTypeStyle).isInstanceOf(IllegalArgumentException.class);
    }
}
