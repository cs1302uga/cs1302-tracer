package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceOutcomeBuilderTest {
    @Test
    void defaultsPreserveAnUnspecifiedOutcomeWithoutInventingJson() {
        TraceOutcome outcome = TraceOutcome.builder().build();

        assertThat(outcome.format()).isNull();
        assertThat(outcome.status()).isNull();
        assertThat(outcome.stopReason()).isNull();
        assertThat(outcome.phase()).isNull();
        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.counters()).isEmpty();
        assertThat(outcome.diagnostics()).isEmpty();
        assertThat(outcome.stdout()).isNull();
        assertThat(outcome.stderr()).isNull();
        assertThat(outcome.traceJson()).isNull();
        assertThat(outcome.json()).isNull();
        assertThat(outcome.toBuilder().build()).isEqualTo(outcome);
    }

    @Test
    void namedConstructionAndCopyPreserveEveryComponent() {
        TraceOutcome expected = new TraceOutcome("modern", "stopped", "snapshot_limit", "trace", false,
                Map.of("snapshots", 3L), List.of("first", "second"), "stdout", "stderr",
                "{\"steps\":[]}", "{\"status\":\"stopped\"}");
        TraceOutcome built = TraceOutcome.builder()
                .format(expected.format())
                .status(expected.status())
                .stopReason(expected.stopReason())
                .phase(expected.phase())
                .complete(expected.complete())
                .counters(expected.counters())
                .diagnostics(expected.diagnostics())
                .stdout(expected.stdout())
                .stderr(expected.stderr())
                .traceJson(expected.traceJson())
                .json(expected.json())
                .build();

        assertThat(built).isEqualTo(expected);
        assertThat(built.toBuilder().build()).isEqualTo(expected).isNotSameAs(built);
    }

    @Test
    void settersDetachCollectionsBeforeBuildAndReuseKeepsEarlierValues() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("snapshots", 1L);
        List<String> diagnostics = new ArrayList<>(List.of("first", "second"));
        TraceOutcome.Builder builder = TraceOutcome.builder().counters(counters).diagnostics(diagnostics);

        counters.put("snapshots", 2L);
        counters.put("threads", 3L);
        diagnostics.clear();
        TraceOutcome first = builder.build();
        TraceOutcome second = builder.counters(Map.of("snapshots", 4L)).diagnostics(List.of("later"))
                .complete(true).build();

        assertThat(first.counters()).containsExactlyEntriesOf(Map.of("snapshots", 1L));
        assertThat(first.diagnostics()).containsExactly("first", "second");
        assertThat(first.complete()).isFalse();
        assertThat(second.counters()).containsExactlyEntriesOf(Map.of("snapshots", 4L));
        assertThat(second.diagnostics()).containsExactly("later");
        assertThat(second.complete()).isTrue();
        assertThatThrownBy(() -> first.counters().put("snapshots", 5L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.diagnostics().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stringsCanBeResetToNullWithoutInterpretingThem() {
        TraceOutcome.Builder builder = TraceOutcome.builder()
                .format("format").status("status").stopReason("reason").phase("phase")
                .stdout("out").stderr("err").traceJson("trace").json("json");
        TraceOutcome original = builder.build();
        TraceOutcome cleared = builder.format(null).status(null).stopReason(null).phase(null)
                .stdout(null).stderr(null).traceJson(null).json(null).build();

        assertThat(cleared).isEqualTo(TraceOutcome.builder().build());
        assertThat(cleared.toBuilder().build()).isEqualTo(cleared);
        assertThat(original).isEqualTo(new TraceOutcome("format", "status", "reason", "phase", false,
                Map.of(), List.of(), "out", "err", "trace", "json"));
    }

    @Test
    void collectionSettersPreserveConstructorNullRestrictions() {
        TraceOutcome.Builder builder = TraceOutcome.builder();
        assertThatThrownBy(() -> builder.counters(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.diagnostics(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.diagnostics(Arrays.asList("valid", null)))
                .isInstanceOf(NullPointerException.class);

        Map<String, Long> nullKey = new HashMap<>();
        nullKey.put(null, 1L);
        assertThatThrownBy(() -> builder.counters(nullKey)).isInstanceOf(NullPointerException.class);
        Map<String, Long> nullValue = new HashMap<>();
        nullValue.put("snapshots", null);
        assertThatThrownBy(() -> builder.counters(nullValue)).isInstanceOf(NullPointerException.class);
        assertThat(builder.build()).isEqualTo(TraceOutcome.builder().build());
    }

    @Test
    void copyingMetadataDoesNotRegenerateJsonAndWritingPreservesSuppliedText() throws Exception {
        String envelope = " {\n  \"stdout\": \"before\"\n}\n";
        TraceOutcome original = TraceOutcome.builder().stdout("before")
                .traceJson("explicit unvalidated trace text").json(envelope).build();
        TraceOutcome changed = original.toBuilder().stdout("after").status("custom-status").build();
        StringWriter destination = new StringWriter();
        changed.writeJson(destination);

        assertThat(changed.stdout()).isEqualTo("after");
        assertThat(changed.status()).isEqualTo("custom-status");
        assertThat(changed.traceJson()).isEqualTo("explicit unvalidated trace text");
        assertThat(changed.json()).isEqualTo(envelope);
        assertThat(destination.toString()).isEqualTo(envelope);
        assertThat(original.stdout()).isEqualTo("before");
        assertThat(original.status()).isNull();
    }
}
