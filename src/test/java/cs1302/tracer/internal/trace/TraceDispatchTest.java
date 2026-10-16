package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;
import cs1302.tracer.internal.SourcePreparation;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceDispatchTest {
    @Test
    void dedicatedGuestReturnsFinalStateWithoutSelectingAnOutputFormat() throws Exception {
        String source = "public class Main { public static void main(String[] args) { System.out.print(\"done\"); } }";
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            var captured = TraceDispatch.dedicated().capture(prepared,
                    TraceDispatch.Selection.select(false, false, null), "");
            assertThat(captured).isInstanceOf(TraceDispatch.FinalState.class);
            var snapshot = ((TraceDispatch.FinalState) captured).snapshot();
            assertThat(new String(snapshot.stdout(), StandardCharsets.UTF_8)).isEqualTo("done");
        }
    }
    @Test
    void selectedBreakpointsCanAccumulateOrKeepTheLatestHit() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        for (int i = 0; i < 2; i++) {
                            System.out.print(i);
                        }
                    }
                }
                """;
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            var dispatcher = TraceDispatch.dedicated();
            var all = (TraceDispatch.Breakpoints) dispatcher.capture(prepared,
                    TraceDispatch.Selection.select(false, true, List.of(BreakpointSpec.of(4))), "");
            assertThat(all.snapshots().get(4)).hasSize(2);
            assertThat(all.accumulated()).isTrue();
            var latest = (TraceDispatch.Breakpoints) dispatcher.capture(prepared,
                    TraceDispatch.Selection.select(false, false, List.of(BreakpointSpec.of(4))), "");
            assertThat(latest.snapshots().get(4)).hasSize(1);
            assertThat(latest.accumulated()).isFalse();
            assertThat(new String(latest.snapshots().get(4).getFirst().stdout(), StandardCharsets.UTF_8))
                    .isEqualTo("01");
        }
    }
    @Test
    void chronologicalCaptureDiscoversLinesAndPreservesExecutionOrder() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.print("a");
                        System.out.print("b");
                    }
                }
                """;
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            var captured = (TraceDispatch.Chronological) TraceDispatch.dedicated().capture(prepared,
                    TraceDispatch.Selection.select(true, false, null), "");
            assertThat(captured.snapshots()).extracting(snapshot -> snapshot.stack().getLast().methodLine())
                    .containsSubsequence(3L, 4L);
            assertThat(new String(captured.snapshots().getLast().stdout(), StandardCharsets.UTF_8)).isEqualTo("ab");
        }
    }
    @Test
    void persistentAdapterReusesGuestWithIsolatedOutputAndProgramState() throws Exception {
        String source = """
                public class Main {
                    static int count;
                    public static void main(String[] args) {
                        System.out.print(++count);
                    }
                }
                """;
        try (var guest = PersistentGuestSession.create();
                var prepared = SourcePreparation.prepare(source, Optional.empty(),
                        SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            var dispatcher = TraceDispatch.persistent(guest);
            var first = (TraceDispatch.FinalState) dispatcher.capture(prepared,
                    TraceDispatch.Selection.select(false, false, null), "");
            var second = (TraceDispatch.Chronological) dispatcher.capture(prepared,
                    TraceDispatch.Selection.select(true, false, List.of(BreakpointSpec.of(4))), "");
            assertThat(new String(first.snapshot().stdout(), StandardCharsets.UTF_8)).isEqualTo("1");
            assertThat(new String(second.snapshots().getLast().stdout(), StandardCharsets.UTF_8)).isEqualTo("1");
            assertThat(guest.isAlive()).isTrue();
        }
    }
}
