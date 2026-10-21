package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SetCliTest {
    @ParameterizedTest
    @CsvSource({"modern,false", "pytutor,false", "modern,true", "pytutor,true"})
    void dedicatedGuestsCaptureSetsAcrossSelections(String format, boolean envelope) throws Exception {
        String source = Files.readString(Path.of("examples/sets/Main.java"));
        int line = source.substring(0, source.indexOf("        System.out.println(alias);")).split("\n").length + 1;
        for (String mode : List.of("final", "selected", "chronological")) {
            List<String> options = new ArrayList<>(List.of("-f", format));
            if (envelope) options.add("--result-envelope");
            if (mode.equals("selected")) options.add("--breakpoints=" + line);
            if (mode.equals("chronological")) options.add("--all-breakpoints");
            String json = AppTest.executeCommand(App.Trace::new, source, options.toArray(String[]::new)).orElseThrow();
            var result = JsonParser.parseString(json).getAsJsonObject();
            if (envelope) {
                assertThat(result.get("complete").getAsBoolean()).isTrue();
                result = result.getAsJsonObject("trace");
            }
            var steps = new ArrayList<JsonElement>();
            collectSteps(result, format.equals("modern") ? "steps" : "trace", steps);
            assertThat(steps).anySatisfy(step -> {
                var heap = step.getAsJsonObject().getAsJsonObject("heap");
                assertThat(heap.entrySet()).anySatisfy(entry -> {
                    var value = entry.getValue();
                    if (format.equals("modern")) {
                        var object = value.getAsJsonObject();
                        assertThat(object.get("type").getAsString()).startsWith("java.util.Collections$UnmodifiableSet");
                        assertThat(object.get("kind").getAsString()).isEqualTo("array");
                        assertThat(object.getAsJsonArray("elements")).hasSize(3);
                    } else {
                        var object = value.getAsJsonArray();
                        assertThat(object.get(0).getAsString()).isEqualTo("SET");
                        assertThat(object).hasSize(4);
                    }
                });
            });
            assertThat(json).contains("[first, null]");
            if (!mode.equals("selected")) assertThat(json).contains("[first, null, last]");
        }
    }

    private static void collectSteps(JsonElement value, String key, List<JsonElement> steps) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("heap")) steps.add(object);
            else if (object.has(key)) steps.addAll(object.getAsJsonArray(key).asList());
            else object.entrySet().forEach(entry -> collectSteps(entry.getValue(), key, steps));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> collectSteps(child, key, steps));
        }
    }
}
