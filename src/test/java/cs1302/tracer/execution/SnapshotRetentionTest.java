package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotRetentionTest {
    @Test
    void rejectedReplacementPreservesHistoryAndAccounting() {
        var limits = new TraceLimits(0, 0, 0, 0, 0, 15_000, 0, 0);
        var retention = new SnapshotRetention(limits, false, () -> {});
        var first = new ExecutionSnapshot(List.of(), List.of(), Map.of(), new byte[0], new byte[0]);
        var replacement = first.withOutput(cs1302.tracer.trace.OutputSlice.from(new byte[] {65}),
                cs1302.tracer.trace.OutputSlice.from(new byte[0]));
        retention.commit(first, 10_000);
        assertThatThrownBy(() -> retention.commit(replacement, 10_000))
                .isInstanceOf(SnapshotRetention.LimitExceeded.class).hasMessage("trace_limit");
        assertThat(retention.snapshots()).containsExactly(first);
        assertThat(retention.retainedBytes()).isEqualTo(10_000);
        assertThat(retention.captured()).isEqualTo(1);
    }
    @Test
    void targetedOutputProjectionRefreshesLatestLocationWithoutChangingBudgetedHistory() {
        var retention = new SnapshotRetention(TraceLimits.unlimited(), true, () -> {});
        var first = new ExecutionSnapshot(List.of(), List.of(), Map.of(), new byte[0], new byte[0],
                java.util.Optional.of("A.java"));
        var other = new ExecutionSnapshot(List.of(), List.of(), Map.of(), new byte[0], new byte[0],
                java.util.Optional.of("B.java"));
        var last = first.withOutput(cs1302.tracer.trace.OutputSlice.from(new byte[] {90}),
                cs1302.tracer.trace.OutputSlice.empty());
        retention.commit(first, 1_000);
        retention.commit(other, 1_000);
        retention.commit(last, 1_000);
        var projected = retention.targetedSnapshots();
        assertThat(projected.get(0)).isSameAs(first);
        assertThat(projected.get(1).stdout()).containsExactly((byte) 90);
        assertThat(projected.get(2).stdout()).containsExactly((byte) 90);
        assertThat(retention.snapshots()).containsExactly(first, other, last);
        assertThat(retention.snapshots().get(1).stdout()).isEmpty();
        assertThat(retention.retainedBytes()).isEqualTo(3_000);
    }
}
