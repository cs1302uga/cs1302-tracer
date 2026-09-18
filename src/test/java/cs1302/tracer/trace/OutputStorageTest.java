package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import cs1302.tracer.execution.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutputStorageTest {
    private ExecutionSnapshot state(String out, String err) {
        return new ExecutionSnapshot(List.of(new ExecutionSnapshot.StackSnapshot(
                "Main.main", 3, List.of(), Optional.empty())), List.of(), Map.of(),
                out.getBytes(StandardCharsets.UTF_8), err.getBytes(StandardCharsets.UTF_8),
                Optional.of("Main.java"), "é", 1);
    }

    @Test void prefixesSurviveGrowthAndMutationOfMaterializedArrays() {
        var storage = new OutputStorage();
        var input = state("é", "warning");
        Snapshot first = storage.capture(input);
        Snapshot second = storage.capture(state("é" + "x".repeat(8192), "warning!"));
        input.stdout()[0] = 0;
        first.stdout()[0] = 0;
        first.materialize().stderr()[0] = 0;
        assertThat(first.stdout()).isEqualTo("é".getBytes(StandardCharsets.UTF_8));
        assertThat(first.stderr()).isEqualTo("warning".getBytes(StandardCharsets.UTF_8));
        assertThat(second.stdout()).hasSize(8194);
        assertThat(first.metadata().stdout()).isEmpty();
        assertThat(first.materialize().stdinConsumed()).isEqualTo("é");
        assertThat(first.materialize().stdinOffset()).isEqualTo(1);
        assertThat(first.materialize().sourcePath()).contains("Main.java");
        assertThat(first.materialize().statics()).isEmpty();
        assertThat(first.materialize().heap()).isEmpty();
        assertThat(first.materialize().stack()).hasSize(1);
        assertThat(input.metadata()).isSameAs(input);
    }

    @Test void changedAndShorterSanitizedOutputForkWithoutChangingEarlierStates() {
        var storage = new OutputStorage();
        var first = storage.capture(state("abc", "banner"));
        var shorter = storage.capture(state("ab", ""));
        var different = storage.capture(state("XY", "user error"));
        var same = storage.capture(state("XY", "user error"));
        assertThat(first.stdout()).isEqualTo("abc".getBytes());
        assertThat(first.stderr()).isEqualTo("banner".getBytes());
        assertThat(shorter.stdout()).isEqualTo("ab".getBytes());
        assertThat(shorter.stderr()).isEmpty();
        assertThat(different.stdout()).isEqualTo(same.stdout());
        same.stdout()[0] = 0;
        assertThat(different.stdout()).isEqualTo("XY".getBytes());
    }

    @Test void refreshPreservesPreviousOutputAndSupportsPublicSnapshots() {
        var original = state("a", "");
        for (Snapshot first : List.of(original, new OutputStorage().capture(original))) {
            var updated = OutputStorage.refresh(first, "ab".getBytes(), "err".getBytes());
            assertThat(first.stdout()).isEqualTo("a".getBytes());
            assertThat(updated.stdout()).isEqualTo("ab".getBytes());
            assertThat(updated.stderr()).isEqualTo("err".getBytes());
        }
    }

    @Test void publicOutputRefreshStillUpdatesTheRetainedPublicRecord() {
        var original = state("a", "");
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            session.beginSnapshot();
            session.commit(original);
            var refreshed = session.updateOutput(original, "abc".getBytes(), "error".getBytes());
            assertThat(session.snapshots().getFirst()).isSameAs(refreshed);
            assertThat(original.stdout()).isEqualTo("a".getBytes());
            assertThat(refreshed.stdout()).isEqualTo("abc".getBytes());
        }
    }

    @Test void compactAndEagerSnapshotsHaveIdenticalLogicalAccounting() {
        var original = state("é😀", "stderr");
        Map<String, Long> expected;
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            session.beginSnapshot();
            session.commit(original);
            expected = session.result("modern", null, null).counters();
        }
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true)) {
            session.beginSnapshot();
            session.commit(new OutputStorage().capture(original), true);
            assertThat(session.result("modern", null, null).counters()).isEqualTo(expected);
            var first = session.snapshots().getFirst();
            first.stdout()[0] = 0;
            assertThat(session.snapshots().getFirst().stdout()).isEqualTo(original.stdout());
        }
    }
}
