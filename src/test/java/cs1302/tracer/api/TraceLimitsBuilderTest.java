package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TraceLimitsBuilderTest {
    @Test
    void factoryUsesTheInstructorPresetAndUnlimitedRemainsExplicit() {
        assertThat(TraceLimits.builder().build()).isEqualTo(TraceLimits.instructorDefaults());
        assertThat(TraceLimits.unlimited().toBuilder().build()).isEqualTo(TraceLimits.unlimited());
        TraceLimits selected = TraceLimits.unlimited().toBuilder().sourceFiles(2).build();
        assertThat(selected).isEqualTo(new TraceLimits(0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0));
    }

    @Test
    void eachNamedBudgetMapsToTheExistingConstructor() {
        TraceLimits limits = TraceLimits.builder()
                .timeoutMillis(1)
                .snapshots(2)
                .outputBytes(3)
                .heapObjects(4)
                .elements(5)
                .traceBytes(6)
                .sourceBytes(7)
                .sourceFiles(8)
                .threads(9)
                .frames(10)
                .snapshotBytes(11)
                .build();
        assertThat(limits).isEqualTo(new TraceLimits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
        assertThat(limits.toBuilder().build()).isEqualTo(limits).isNotSameAs(limits);
    }

    @Test
    void copiesAndRepeatedBuildsDoNotChangeEarlierLimits() {
        TraceLimits original = new TraceLimits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        TraceLimits.Builder builder = original.toBuilder();
        TraceLimits first = builder.build();
        TraceLimits second = builder.timeoutMillis(0).snapshotBytes(100).build();
        assertThat(original).isEqualTo(first);
        assertThat(second).isEqualTo(new TraceLimits(0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 100));
        assertThat(builder.build()).isEqualTo(second).isNotSameAs(second);
    }

    @Test
    void negativeBudgetsFailAtBuildAndCanBeCorrected() {
        List<Consumer<TraceLimits.Builder>> invalidSettings = List.of(
                builder -> builder.timeoutMillis(-1),
                builder -> builder.snapshots(-1),
                builder -> builder.outputBytes(-1),
                builder -> builder.heapObjects(-1),
                builder -> builder.elements(-1),
                builder -> builder.traceBytes(-1),
                builder -> builder.sourceBytes(-1),
                builder -> builder.sourceFiles(-1),
                builder -> builder.threads(-1),
                builder -> builder.frames(-1),
                builder -> builder.snapshotBytes(-1));
        for (Consumer<TraceLimits.Builder> setting : invalidSettings) {
            TraceLimits.Builder builder = TraceLimits.unlimited().toBuilder();
            setting.accept(builder);
            assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Trace limits must be nonnegative; 0 is unlimited");
        }
        TraceLimits.Builder builder = TraceLimits.builder().snapshots(-1);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.snapshots(0).build().snapshots()).isZero();
    }

    @Test
    void timeoutPreservesTheNanosecondConversionBoundary() {
        long maximum = Long.MAX_VALUE / 1_000_000;
        TraceLimits.Builder builder = TraceLimits.builder().timeoutMillis(maximum);
        assertThat(builder.build().timeoutMillis()).isEqualTo(maximum);
        builder.timeoutMillis(maximum + 1);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Timeout is too large");
        assertThat(builder.timeoutMillis(0).build().timeoutMillis()).isZero();
    }
}
