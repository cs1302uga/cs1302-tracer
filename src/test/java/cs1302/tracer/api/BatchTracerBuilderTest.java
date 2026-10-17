package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BatchTracerBuilderTest {
    @Test
    void requiresAllNumericSettingsWithoutTreatingZeroAsMissing() {
        BatchTracer.Builder builder = BatchTracer.builder();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workers");
        builder.workers(1);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxJobsPerWorker");
        builder.maxJobsPerWorker(100);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxInFlight");
        builder.maxInFlight(0);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        try (BatchTracer tracer = builder.maxInFlight(1).build()) {
            assertThat(tracer).isNotNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1,1", "-1,1,1", "1,0,1", "1,-1,1", "1,1,0", "1,1,-1"})
    void usesExistingNumericValidation(int workers, int recycling, int admission) {
        BatchTracer.Builder builder = BatchTracer.builder().workers(workers)
                .maxJobsPerWorker(recycling).maxInFlight(admission);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(60)
    void reusedConfigurationCreatesIndependentProcessorsAndJobOutput() throws Exception {
        BatchTracer.Builder builder = BatchTracer.builder().workers(1).maxJobsPerWorker(100).maxInFlight(2);
        try (BatchTracer first = builder.build()) {
            try (BatchTracer second = builder.completionOrder(true).build()) {
                assertThat(first).isNotSameAs(second);
                assertThat(process(second, request("independent", "9"))).singleElement()
                        .satisfies(result -> assertThat(result.getAsJsonObject("result")
                                .get("stdout").getAsString()).isEqualTo("9"));
            }
            List<JsonObject> results = process(first, request("first", "42") + request("second", "7"));
            assertThat(results).extracting(result -> result.get("id").getAsString())
                    .containsExactly("first", "second");
            assertThat(results).extracting(result -> result.getAsJsonObject("result").get("stdout").getAsString())
                    .containsExactly("42", "7");
            assertThat(process(first, request("third", "3"))).singleElement()
                    .satisfies(result -> assertThat(result.getAsJsonObject("result")
                            .get("stdout").getAsString()).isEqualTo("3"));
        }
    }

    private static String request(String id, String value) {
        String source = "public class Main { public static void main(String[] args) { System.out.print("
                + value + "); } }";
        return new Gson().toJson(Map.of("id", id, "source", source)) + "\n";
    }

    private static List<JsonObject> process(BatchTracer tracer, String input) throws Exception {
        StringWriter output = new StringWriter();
        tracer.processStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        return output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
