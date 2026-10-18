package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.api.TraceFormat;
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
import org.junit.jupiter.params.provider.EnumSource;

class LambdaTypeStyleIntegrationTest {

    private static final String SOURCE = """
        public class Main {
            public static void main(String[] args) {
                java.util.function.Function<Integer, String> f = value -> {
                    // java.lang. in this body must remain unchanged.
                    return java.lang.String.valueOf(value) + "java.lang.";
                };
                System.out.print(f.apply(42));
            }
        }
        """;

    @ParameterizedTest
    @CsvSource({"modern,simple", "modern,fqn", "pytutor,simple", "pytutor,fqn"})
    void cliFormatsGeneratedSignatureOnly(String format, String style) {
        String json = AppTest.executeCommand(App.Trace::new, SOURCE, "--format=" + format,
                "--type-style=" + style).orElseThrow();
        assertImplementation(JsonParser.parseString(json), style.equals("simple"));
    }

    @ParameterizedTest
    @EnumSource(TypeStyle.class)
    void multithreadJavaApiUsesTheSamePresentation(TypeStyle style) {
        TraceOutcome outcome = Tracer.trace(TraceRequest.builder(SOURCE).format(TraceFormat.MODERN)
                .typeStyle(style).multithread(true).build());
        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.stdout()).isEqualTo("42java.lang.");
        assertImplementation(JsonParser.parseString(outcome.traceJson()), style == TypeStyle.SIMPLE);
    }

    @Test
    void consecutivePersistentGuestJobsKeepFormatsAndStylesIndependent() throws Exception {
        List<String> jobs = new ArrayList<>();
        for (String format : List.of("modern", "pytutor")) {
            for (String style : List.of("simple", "fqn", "simple")) {
                jobs.add(new Gson().toJson(Map.of("source", SOURCE, "format", format, "typeStyle", style)));
            }
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 100, 2, false)) {
            batch.processStream(new ByteArrayInputStream((String.join("\n", jobs) + "\n")
                    .getBytes(StandardCharsets.UTF_8)), output);
        }
        String[] responses = output.toString().split("\n");
        assertThat(responses).hasSize(6);
        for (int i = 0; i < responses.length; i++) {
            JsonObject result = JsonParser.parseString(responses[i]).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("status").getAsString()).isEqualTo("completed");
            assertThat(result.get("stdout").getAsString()).isEqualTo("42java.lang.");
            assertImplementation(result, i % 3 != 1);
        }
    }

    private static void assertImplementation(JsonElement root, boolean simple) {
        List<String> implementations = new ArrayList<>();
        collect(root, implementations);
        assertThat(implementations).isNotEmpty().allSatisfy(implementation -> {
            assertThat(implementation).startsWith(simple ? "String apply(Integer value)" :
                    "java.lang.String apply(java.lang.Integer value)");
            assertThat(implementation).contains("return java.lang.String.valueOf(value) + \"java.lang.\";");
        });
    }

    private static void collect(JsonElement value, List<String> implementations) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("kind") && object.get("kind").getAsString().equals("lambda")) {
                implementations.add(object.get("sam").getAsString());
            }
            object.asMap().values().forEach(child -> collect(child, implementations));
        } else if (value.isJsonArray()) {
            if (value.getAsJsonArray().size() == 2 && value.getAsJsonArray().get(0).isJsonPrimitive()
                    && value.getAsJsonArray().get(0).getAsString().equals("JAVA_LAMBDA")) {
                implementations.add(value.getAsJsonArray().get(1).getAsString());
            }
            value.getAsJsonArray().forEach(child -> collect(child, implementations));
        }
    }

}
