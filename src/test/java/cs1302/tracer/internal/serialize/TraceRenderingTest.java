package cs1302.tracer.internal.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import cs1302.tracer.internal.CompilationHelper;
import cs1302.tracer.internal.SubmittedSources;
import com.google.gson.JsonParser;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.internal.batch.BatchJobResponse;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.TraceDispatch;
import cs1302.tracer.internal.trace.TraceValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TraceRenderingTest {
    private static final SubmittedSources SOURCE = CompilationHelper.parseSources(
            "public class Main { public static void main(String[] args) {} }").submitted();
    private static final ExecutionSnapshot SNAPSHOT = new ExecutionSnapshot(List.of(),
        List.of(new ExecutionSnapshot.Field(false, "double", "number", new TraceValue.Primitive.Double(Double.NaN)),
            new ExecutionSnapshot.Field(false, "Object", "nothing", new TraceValue.Null())),
        Map.of(), new byte[0], new byte[0]);

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void ordinaryRootsAndWhitespaceRemainModeSpecific(TraceFormat format) {
        TraceJob job = job(format, false);
        for (boolean pretty : List.of(false, true)) {
            String finalJson = TraceRendering.ordinary(SOURCE, "", job,
                new TraceDispatch.FinalState(SNAPSHOT), pretty);
            JsonObject chronological = parse(TraceRendering.ordinary(SOURCE, "", job,
                new TraceDispatch.Chronological(List.of(SNAPSHOT)), pretty));
            assertThat(parse(finalJson)).isEqualTo(chronological);
            assertThat(finalJson.contains("\n")).isEqualTo(pretty || format == TraceFormat.MODERN);
            assertThat(finalJson).doesNotContain("\"binary\"", "elementBinary");
            for (boolean accumulate : List.of(false, true)) {
                JsonObject root = parse(TraceRendering.ordinary(SOURCE, "", job,
                    new TraceDispatch.Breakpoints(Map.of(1, List.of(SNAPSHOT), 2, List.of(SNAPSHOT, SNAPSHOT),
                        3, List.of()), accumulate), pretty));
                JsonObject selected = format == TraceFormat.MODERN ? root.getAsJsonObject("breakpoints") : root;
                assertThat(selected.get("1").isJsonArray()).isEqualTo(accumulate);
                assertThat(selected.getAsJsonArray("2")).hasSize(2);
                assertThat(selected.getAsJsonArray("3")).isEmpty();
                assertThat(root.has(format == TraceFormat.MODERN ? "steps" : "trace")).isFalse();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void envelopesAndBatchRetainNullsAndMetadataPolicy(TraceFormat format) {
        for (boolean enabled : List.of(false, true)) {
            TraceJob job = job(format, enabled);
            Object payload = TraceRendering.sequence(SOURCE, "", job, List.of(SNAPSHOT));
            TraceResult result = new TraceResult(1, format.name().toLowerCase(java.util.Locale.ROOT),
                "completed", null, "serialize", true, payload, TraceLimits.unlimited(), Map.of(), List.of(), "", "");
            for (boolean pretty : List.of(false, true)) {
                var detached = TraceRendering.freeze(result, pretty);
                JsonObject envelope = parse(detached.json());
                assertThat(envelope.get("stopReason").isJsonNull()).isTrue();
                assertThat(envelope.get("trace")).isEqualTo(JsonParser.parseString(detached.traceJson()));
                assertThat(detached.traceJson().contains("\"binary\"")).isEqualTo(enabled);
                assertThat(detached.json().contains("\n")).isEqualTo(pretty);
                JsonObject batch = parse(TraceRendering.batch(new BatchJobResponse("job\n1", result)));
                assertThat(batch.get("result")).isEqualTo(envelope);
                assertThat(batch.get("id").getAsString()).isEqualTo("job\n1");
                if (format == TraceFormat.MODERN) {
                    assertThat(envelope.getAsJsonObject("trace").get("breakpoints").isJsonNull()).isTrue();
                }
            }
        }
        JsonObject failed = parse(TraceRendering.freeze(TraceResult.failed("modern", "compile", "bad source"),
            false).json());
        assertThat(failed.get("trace").isJsonNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frozenMetadataAndJsonComeFromTheSameExecutionResult(boolean pretty) {
        TraceResult result = new TraceResult(1, "modern", "stopped", "snapshot_limit", "trace", false,
                Map.of("steps", List.of()), TraceLimits.instructorDefaults(), Map.of("snapshots", 3L),
                List.of("first diagnostic", "second diagnostic"), "out\n\"quoted\"", "err\n");
        TraceOutcome detached = TraceRendering.freeze(result, pretty);
        JsonObject envelope = parse(detached.json());

        assertThat(detached.format()).isEqualTo(result.format());
        assertThat(detached.status()).isEqualTo(result.status());
        assertThat(detached.stopReason()).isEqualTo(result.stopReason());
        assertThat(detached.phase()).isEqualTo(result.phase());
        assertThat(detached.complete()).isEqualTo(result.complete());
        assertThat(detached.counters()).isEqualTo(result.counters());
        assertThat(detached.diagnostics()).isEqualTo(result.diagnostics());
        assertThat(detached.stdout()).isEqualTo(result.stdout());
        assertThat(detached.stderr()).isEqualTo(result.stderr());
        assertThat(envelope.get("format").getAsString()).isEqualTo(detached.format());
        assertThat(envelope.get("status").getAsString()).isEqualTo(detached.status());
        assertThat(envelope.get("stopReason").getAsString()).isEqualTo(detached.stopReason());
        assertThat(envelope.get("phase").getAsString()).isEqualTo(detached.phase());
        assertThat(envelope.get("complete").getAsBoolean()).isEqualTo(detached.complete());
        assertThat(envelope.getAsJsonObject("counters").get("snapshots").getAsLong())
                .isEqualTo(detached.counters().get("snapshots"));
        assertThat(envelope.getAsJsonArray("diagnostics").asList().stream()
                .map(com.google.gson.JsonElement::getAsString).toList()).isEqualTo(detached.diagnostics());
        assertThat(envelope.get("stdout").getAsString()).isEqualTo(detached.stdout());
        assertThat(envelope.get("stderr").getAsString()).isEqualTo(detached.stderr());
        assertThat(envelope.get("trace")).isEqualTo(JsonParser.parseString(detached.traceJson()));
        assertThat(envelope.getAsJsonObject("trace").getAsJsonArray("steps")).isEmpty();
        assertThat(envelope.get("schemaVersion").getAsInt()).isEqualTo(result.schemaVersion());
        assertThat(envelope.getAsJsonObject("limits").get("snapshots").getAsLong())
                .isEqualTo(result.limits().snapshots());
    }

    private static TraceJob job(TraceFormat format, boolean bits) {
        return TraceJob.resolve(TraceJob.Profile.CLI, format, TypeStyle.FQN, null, null, null,
            false, false, false, false, false, false, true, bits);
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
