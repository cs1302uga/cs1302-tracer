package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaTimeCliTest {
    @ParameterizedTest
    @CsvSource({"modern,false", "pytutor,false", "modern,true", "pytutor,true"})
    void dedicatedGuestsCaptureExampleAcrossSelections(String format, boolean envelope) throws Exception {
        String source = Files.readString(Path.of("examples/java-time/Main.java"));
        int printLine = source.substring(0, source.indexOf("        System.out.println(meeting);")).split("\n").length + 1;
        for (String mode : List.of("final", "selected", "chronological")) {
            List<String> options = new ArrayList<>(List.of("-f", format, "--include-primitive-bits", "--type-style=simple"));
            if (envelope) options.add("--result-envelope");
            if (mode.equals("selected")) options.add("--breakpoints=" + printLine);
            if (mode.equals("chronological")) options.add("--all-breakpoints");
            String json = AppTest.executeCommand(App.Trace::new, source, options.toArray(String[]::new)).orElseThrow();
            JsonElement result = JsonParser.parseString(json);
            if (envelope) assertThat(result.getAsJsonObject().get("status").getAsString()).isEqualTo("completed");
            assertThat(hasDuration(result, format)).as(mode + " " + json).isTrue();
            assertThat(json).contains("1111111111111111111111111111111111111111111111111111111111111111");
            assertThat(json).contains("2024-02-29T12:30:00.123456789", "PT-0.000000001S", "PT1.999999999S");
        }
    }

    private static boolean hasDuration(JsonElement value, String format) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (format.equals("modern") && object.has("kind") && object.has("type")
                    && object.get("type").getAsString().equals("Duration")) {
                assertThat(object.get("kind").getAsString()).isEqualTo("object");
                assertThat(object.getAsJsonArray("fields")).hasSize(2);
                var seconds = object.getAsJsonArray("fields").get(0).getAsJsonObject();
                assertThat(seconds.get("name").getAsString()).isEqualTo("seconds");
                assertThat(seconds.get("type").getAsString()).isEqualTo("long");
                assertThat(seconds.get("final").getAsBoolean()).isTrue();
                assertThat(seconds.getAsJsonObject("binary").get("bits").getAsString()).hasSize(64);
                return true;
            }
            return object.entrySet().stream().anyMatch(entry -> hasDuration(entry.getValue(), format));
        }
        if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            if (format.equals("pytutor") && array.size() > 1 && array.get(0).isJsonPrimitive()
                    && array.get(0).getAsString().equals("INSTANCE") && array.get(1).getAsString().equals("Duration")) {
                assertThat(array).hasSize(4);
                assertThat(array.get(2).getAsJsonArray().get(0).getAsString()).isEqualTo("seconds");
                assertThat(array.get(3).getAsJsonArray().get(0).getAsString()).isEqualTo("nanos");
                return true;
            }
            for (JsonElement child : array) if (hasDuration(child, format)) return true;
        }
        return false;
    }
}
