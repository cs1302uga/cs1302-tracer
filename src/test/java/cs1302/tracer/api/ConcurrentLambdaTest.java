package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ConcurrentLambdaTest {
    @Test
    void concurrentBatchJobsRetainEveryLambdaImplementation() throws Exception {
        StringBuilder source = new StringBuilder("public class Main {\n");
        for (int i = 0; i < 24; i++) {
            source.append("static java.util.function.IntUnaryOperator op").append(i)
                    .append(" = x -> x + ").append(i).append(";\n");
        }
        source.append("public static void main(String[] args) { System.out.print(op23.applyAsInt(19)); }\n}");
        StringBuilder requests = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            requests.append(new com.google.gson.Gson().toJson(java.util.Map.of(
                    "id", Integer.toString(i), "source", source.toString()))).append('\n');
        }
        java.io.StringWriter output = new java.io.StringWriter();
        try (BatchTracer batch = new BatchTracer(4, 100, 8, false)) {
            batch.processStream(new java.io.ByteArrayInputStream(
                    requests.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), output);
        }
        String[] responses = output.toString().split("\n");
        assertThat(responses).hasSize(16);
        for (String response : responses) {
            com.google.gson.JsonObject result = com.google.gson.JsonParser.parseString(response).getAsJsonObject()
                    .getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(response).isTrue();
            assertThat(result.get("stdout").getAsString()).isEqualTo("42");
            com.google.gson.JsonObject heap = result.getAsJsonObject("trace").getAsJsonArray("trace")
                    .get(0).getAsJsonObject().getAsJsonObject("heap");
            long lambdas = heap.asMap().values().stream()
                    .filter(value -> value.getAsJsonArray().get(0).getAsString().equals("JAVA_LAMBDA")).count();
            assertThat(lambdas).as(response).isEqualTo(24);
            com.google.gson.JsonObject globals = result.getAsJsonObject("trace").getAsJsonArray("trace")
                    .get(0).getAsJsonObject().getAsJsonObject("globals");
            String first = globals.getAsJsonArray("Main.op0").get(1).getAsString();
            String last = globals.getAsJsonArray("Main.op23").get(1).getAsString();
            assertThat(heap.getAsJsonArray(first).get(1).getAsString()).contains("return x + 0;");
            assertThat(heap.getAsJsonArray(last).get(1).getAsString()).contains("return x + 23;");
        }
    }
}
