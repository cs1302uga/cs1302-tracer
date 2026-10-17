package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TraceRequestBuilderTest {
    @Test
    void defaultsExactlyMatchTheConvenienceConstructor() {
        String source = "class Main {}";
        assertThat(TraceRequest.builder(source).build()).isEqualTo(new TraceRequest(source));
        assertThatThrownBy(() -> TraceRequest.builder(null))
                .isInstanceOf(NullPointerException.class).hasMessage("source");
    }

    @Test
    void namedSettingsMatchTheCanonicalConstructorAndCopyWithoutLoss() {
        TraceLimits limits = TraceLimits.unlimited().toBuilder().sourceBytes(100).build();
        TraceRequest expected = new TraceRequest("changed source", "input\n", TraceFormat.MODERN,
                List.of("Main.java:3", "Main.java:5"), true, true, true, true, true, true,
                TypeStyle.SIMPLE, limits, InspectionPolicy.FIELDS);
        TraceRequest request = TraceRequest.builder("original source")
                .source("changed source")
                .stdin("input\n")
                .format(TraceFormat.MODERN)
                .breakpoints(List.of("Main.java:3", "Main.java:5"))
                .chronological(true)
                .accumulate(true)
                .multithread(true)
                .removeMainArgs(true)
                .inlineStrings(true)
                .removeMethodThis(true)
                .typeStyle(TypeStyle.SIMPLE)
                .limits(limits)
                .inspection(InspectionPolicy.FIELDS)
                .build();
        assertThat(request).isEqualTo(expected);
        assertThat(request.toBuilder().build()).isEqualTo(expected).isNotSameAs(request);
    }

    @Test
    void copiesRetainIndependentSettingsAndCanResetBooleanChoices() {
        TraceRequest original = new TraceRequest("source", "input", TraceFormat.MODERN, List.of("Main:3"),
                true, true, true, true, true, true, TypeStyle.SIMPLE, TraceLimits.unlimited(),
                InspectionPolicy.FIELDS);
        TraceRequest.Builder builder = original.toBuilder();
        TraceRequest copy = builder.build();
        TraceRequest changed = builder.source("replacement")
                .chronological(false).accumulate(false).multithread(false).removeMainArgs(false)
                .inlineStrings(false).removeMethodThis(false).build();
        assertThat(original).isEqualTo(copy);
        assertThat(changed).isEqualTo(new TraceRequest("replacement", "input", TraceFormat.MODERN,
                List.of("Main:3"), false, false, false, false, false, false, TypeStyle.SIMPLE,
                TraceLimits.unlimited(), InspectionPolicy.FIELDS));
        assertThat(builder.build()).isEqualTo(changed).isNotSameAs(changed);
    }

    @Test
    void breakpointSelectionDetachesAtAssignmentAndSurvivesBuilderReuse() {
        List<String> selected = new ArrayList<>(List.of("Main:8", "Main:3"));
        TraceRequest.Builder builder = TraceRequest.builder("source").breakpoints(selected);
        selected.clear();
        TraceRequest first = builder.build();
        TraceRequest second = builder.breakpoints(List.of("Main:10")).build();
        assertThat(first.breakpoints()).containsExactly("Main:8", "Main:3");
        assertThat(second.breakpoints()).containsExactly("Main:10");
        assertThat(first.toBuilder().build().breakpoints()).containsExactly("Main:8", "Main:3");
        assertThatThrownBy(() -> first.breakpoints().add("Main:9"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void breakpointSetterPreservesExistingNonNullRestrictions() {
        TraceRequest.Builder builder = TraceRequest.builder("source").breakpoints(List.of("Main:3"));
        assertThatThrownBy(() -> builder.breakpoints(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.breakpoints(Arrays.asList("Main:3", null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.build().breakpoints()).containsExactly("Main:3");
    }

    @Test
    void requiredScalarValuesAreValidatedAtBuildAndCanBeCorrected() {
        List<Consumer<TraceRequest.Builder>> invalidSettings = List.of(
                builder -> builder.source(null),
                builder -> builder.stdin(null),
                builder -> builder.format(null),
                builder -> builder.typeStyle(null),
                builder -> builder.limits(null),
                builder -> builder.inspection(null));
        for (Consumer<TraceRequest.Builder> setting : invalidSettings) {
            TraceRequest.Builder builder = TraceRequest.builder("source");
            setting.accept(builder);
            assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class);
        }
        TraceRequest.Builder builder = TraceRequest.builder("source").stdin(null);
        assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class).hasMessage("stdin");
        assertThat(builder.stdin("").build()).isEqualTo(new TraceRequest("source"));
    }

    @Test
    void constructionPreservesUnparsedAndRequestedSettings() {
        TraceRequest request = TraceRequest.builder("invalid Java")
                .breakpoints(List.of("not a breakpoint"))
                .format(TraceFormat.PYTUTOR)
                .multithread(true)
                .inspection(InspectionPolicy.TRUSTED)
                .build();
        assertThat(request.source()).isEqualTo("invalid Java");
        assertThat(request.breakpoints()).containsExactly("not a breakpoint");
        assertThat(request.format()).isEqualTo(TraceFormat.PYTUTOR);
        assertThat(request.multithread()).isTrue();
        assertThat(request.chronological()).isFalse();
        assertThat(request.inspection()).isEqualTo(InspectionPolicy.TRUSTED);
    }
}
