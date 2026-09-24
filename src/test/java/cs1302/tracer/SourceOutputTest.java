package cs1302.tracer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SourceOutputTest {

    @Test
    void sourceKeysMatchRealDebugLocationsInBothFormats() {
        String unused = "package demo;\npublic class Unused {}\n";
        String main = "package demo;\npublic class Main {\n"
                + "  public static void main(String[] args) {\n"
                + "    System.out.println(42);\n  }\n}\n";
        String stream = "// --- src/demo/Unused.java ---\n" + unused
                + "// --- src/demo/Main.java ---\n" + main;
        for (String format : new String[] {"pytutor", "modern"}) {
            for (String input : new String[] {main, stream}) {
                String output = AppTest.executeCommand(App.Trace::new, input, "-f", format)
                        .orElseThrow();
                JsonObject root = JsonParser.parseString(output).getAsJsonObject();
                assertThat(root.get("code").getAsString()).isEqualTo(input);
                assertThat(root.get("entryFile").getAsString()).isEqualTo("demo/Main.java");
                JsonObject sources = root.getAsJsonObject("sources");
                assertThat(sources.get("demo/Main.java").getAsString()).isEqualTo(main);
                assertThat(sources.size()).isEqualTo(input.equals(stream) ? 2 : 1);
                if (input.equals(stream)) {
                    assertThat(sources.get("demo/Unused.java").getAsString()).isEqualTo(unused);
                }
                String steps = format.equals("modern") ? "steps" : "trace";
                String frames = format.equals("modern") ? "callStack" : "stack_to_render";
                JsonObject step = root.getAsJsonArray(steps).get(0).getAsJsonObject();
                assertThat(step.get("file").getAsString()).isEqualTo("demo/Main.java");
                JsonObject frame = step.getAsJsonArray(frames).get(0).getAsJsonObject();
                assertThat(frame.get("file").getAsString()).isEqualTo("demo/Main.java");
            }
        }
    }
}
