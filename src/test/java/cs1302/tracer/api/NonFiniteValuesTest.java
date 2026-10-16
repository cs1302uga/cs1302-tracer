package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NonFiniteValuesTest {
    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void tracesNonFiniteFloatingPointValues(TraceFormat format) {
        TraceOutcome outcome = Tracer.trace(new TraceRequest("""
                public class Main {
                    public static void main(String[] args) {
                        float nan = Float.NaN;
                        float positive = Float.POSITIVE_INFINITY;
                        float negative = Float.NEGATIVE_INFINITY;
                        double doubleNan = Double.NaN;
                        System.out.print("done");
                    }
                }
                """, "", format, java.util.List.of(), false, false, false, false, false, false,
                TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED));
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("done");
        com.google.gson.JsonObject trace = com.google.gson.JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        if (format == TraceFormat.MODERN) {
            com.google.gson.JsonArray locals = trace.getAsJsonArray("steps").get(0).getAsJsonObject()
                    .getAsJsonArray("callStack").get(0).getAsJsonObject().getAsJsonArray("locals");
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (com.google.gson.JsonElement local : locals) {
                com.google.gson.JsonObject variable = local.getAsJsonObject();
                if (!variable.get("name").getAsString().equals("args")) {
                    assertThat(variable.getAsJsonPrimitive("value").isString()).isTrue();
                    values.put(variable.get("name").getAsString(), variable.get("value").getAsString());
                }
            }
            assertThat(values).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                    "nan", "NaN", "positive", "Infinity", "negative", "-Infinity", "doubleNan", "NaN"));
        } else {
            com.google.gson.JsonObject locals = trace.getAsJsonArray("trace").get(0).getAsJsonObject()
                    .getAsJsonArray("stack_to_render").get(0).getAsJsonObject().getAsJsonObject("encoded_locals");
            assertThat(locals.get("nan").toString()).isEqualTo("[\"NUMBER-LITERAL\",\"NaN\"]");
            assertThat(locals.get("positive").toString()).isEqualTo("[\"NUMBER-LITERAL\",\"Infinity\"]");
            assertThat(locals.get("negative").toString()).isEqualTo("[\"NUMBER-LITERAL\",\"-Infinity\"]");
            assertThat(locals.get("doubleNan").toString()).isEqualTo("[\"SPECIAL_FLOAT\",\"NaN\"]");
        }
    }
}
