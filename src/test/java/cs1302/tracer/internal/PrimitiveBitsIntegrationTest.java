package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import cs1302.tracer.api.TypeStyle;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrimitiveBitsIntegrationTest {
    private static final String SOURCE = """
        public class Main {
            static byte global = -1;
            byte field = -1;
            public static void main(String[] args) {
                byte local = -1;
                byte[] array = { -1 };
                Main object = new Main();
                Byte box = local;
                float negativeZero = -0.0f;
                System.out.print(local + array[0] + object.field + box);
            }
        }
        """;

    @ParameterizedTest
    @CsvSource({"modern,false", "pytutor,false", "modern,true", "pytutor,true"})
    void cliSupportsFinalSelectedAndChronologicalMetadata(String format, boolean envelope) {
        for (String mode : List.of("final", "selected", "chronological")) {
            List<String> options = new ArrayList<>(List.of("-f", format, "--include-primitive-bits"));
            if (envelope) {
                options.add("--result-envelope");
                options.add("--inspection=FIELDS");
            }
            if (mode.equals("selected")) {
                options.add("--breakpoints=10");
            } else if (mode.equals("chronological")) {
                options.add("--all-breakpoints");
            }
            String json = AppTest.executeCommand(App.Trace::new, SOURCE, options.toArray(String[]::new)).orElseThrow();
            assertMetadata(JsonParser.parseString(json));
            assertThat(json).contains("elementBinary");
            if (envelope) {
                assertThat(JsonParser.parseString(json).getAsJsonObject().get("status").getAsString())
                    .isEqualTo("completed");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"MODERN,TRUSTED", "PYTUTOR,TRUSTED", "MODERN,FIELDS", "PYTUTOR,FIELDS"})
    void javaApiDetachesValuesAndPreservesOldConstructor(TraceFormat format, InspectionPolicy inspection) {
        TraceRequest request = new TraceRequest(SOURCE, "", format, List.of(), true, false, false, false, false, false,
            TypeStyle.FQN, TraceLimits.instructorDefaults(), inspection);
        TraceOutcome result = Tracer.trace(request, true);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.stdout()).isEqualTo("-4");
        assertMetadata(JsonParser.parseString(result.traceJson()));
        TraceRequest old = new TraceRequest(SOURCE, "", format, List.of(), false, false, false, false, false, false,
            TypeStyle.FQN, TraceLimits.instructorDefaults(), inspection);
        assertThat(TraceRequest.class.getRecordComponents()).hasSize(13);
        String plain = Tracer.trace(old).traceJson();
        assertThat(plain).doesNotContain("\"binary\"", "elementBinary", "fieldBinary");
        assertMetadata(JsonParser.parseString(result.traceJson()));
    }

    @Test
    void batchKeepsEnabledDisabledAndOmittedSettingsJobScoped() throws Exception {
        List<String> requests = new ArrayList<>();
        for (String format : List.of("modern", "pytutor")) {
            for (String setting : List.of("true", "false", "true", "omitted")) {
                JsonObject request = new Gson().toJsonTree(Map.of("source", SOURCE, "format", format,
                    "allBreakpoints", true)).getAsJsonObject();
                if (!setting.equals("omitted")) {
                    request.addProperty("includePrimitiveBits", Boolean.parseBoolean(setting));
                }
                requests.add(request.toString());
            }
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 100, 2, false)) {
            batch.processStream(new ByteArrayInputStream((String.join("\n", requests) + "\n")
                .getBytes(StandardCharsets.UTF_8)), output);
        }
        String[] lines = output.toString().split("\n");
        assertThat(lines).hasSize(8);
        for (int i = 0; i < lines.length; i++) {
            JsonObject result = JsonParser.parseString(lines[i]).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("status").getAsString()).isEqualTo("completed");
            if (i % 2 == 0) {
                assertMetadata(result);
            } else {
                assertThat(lines[i]).doesNotContain("\"binary\"", "elementBinary", "fieldBinary");
            }
        }
    }

    @Test
    void multithreadIncludesBinaryMetadataInThreadStacks() {
        String threaded = SOURCE.replace("main(String[] args) {", "main(String[] args) throws Exception {")
            .replace("System.out.print(local", """
                Thread worker = new Thread(() -> {
                    byte workerValue = -1;
                    System.out.print(workerValue);
                });
                worker.start();
                worker.join();
                System.out.print(local""");
        TraceOutcome result = Tracer.trace(new TraceRequest(threaded, "", TraceFormat.MODERN, List.of(), true, false,
            true, false, false, false, TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED), true);
        assertThat(result.status()).isEqualTo("completed");
        JsonObject trace = JsonParser.parseString(result.traceJson()).getAsJsonObject();
        boolean found = false;
        for (JsonElement step : trace.getAsJsonArray("steps")) {
            JsonElement threads = step.getAsJsonObject().get("threads");
            if (threads != null && threads.toString().contains("workerValue")) {
                assertMetadata(threads);
                found = true;
            }
        }
        assertThat(found).isTrue();
        TraceOutcome unsupported = Tracer.trace(new TraceRequest(SOURCE, "", TraceFormat.PYTUTOR, List.of(), true,
            false, true, false, false, false, TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.FIELDS),
            true);
        assertThat(unsupported.status()).isEqualTo("failed");
    }

    @Test
    void snapshotLimitRetainsCompletedMetadataAndSameStopSemantics() {
        for (boolean enabled : List.of(false, true)) {
            TraceOutcome result = Tracer.trace(new TraceRequest(SOURCE, "", TraceFormat.MODERN, List.of(), true, false,
                false, false, false, false, TypeStyle.FQN, new TraceLimits(10000, 6, 0, 0, 0, 0, 0, 0),
                InspectionPolicy.FIELDS), enabled);
            assertThat(result.status()).isEqualTo("stopped");
            assertThat(result.stopReason()).isEqualTo("snapshot_limit");
            assertThat(JsonParser.parseString(result.traceJson()).getAsJsonObject().getAsJsonArray("steps")).hasSize(6);
            if (enabled) {
                assertMetadata(JsonParser.parseString(result.traceJson()));
            }
        }
    }

    private static void assertMetadata(JsonElement json) {
        List<JsonObject> descriptors = new ArrayList<>();
        collectDescriptors(json, descriptors);
        assertThat(descriptors).isNotEmpty();
        assertThat(descriptors).anySatisfy(descriptor -> {
            assertThat(descriptor.get("type").getAsString()).isEqualTo("byte");
            assertThat(descriptor.get("encoding").getAsString()).isEqualTo("twos-complement");
            assertThat(descriptor.get("bits").getAsString()).isEqualTo("11111111");
        });
    }

    private static void collectDescriptors(JsonElement json, List<JsonObject> descriptors) {
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("bits") && object.has("encoding")) {
                descriptors.add(object);
            }
            object.entrySet().forEach(entry -> collectDescriptors(entry.getValue(), descriptors));
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(value -> collectDescriptors(value, descriptors));
        }
    }
}
