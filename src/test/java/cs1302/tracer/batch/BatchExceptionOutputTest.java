package cs1302.tracer.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Regression coverage for uncaught exception output in the persistent batch protocol. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BatchExceptionOutputTest {

    @ParameterizedTest
    @CsvSource({
        "Object node = null; node.toString();,java.lang.NullPointerException",
        "throw new IllegalStateException(\"student detail\");,java.lang.IllegalStateException",
        "int zero = 0; int result = 1 / zero;,java.lang.ArithmeticException"
    })
    void uncaughtOutputAndRecovery(String statement, String exceptionClass) throws Exception {
        String source = """
                public class Driver {
                    public static void main(String[] args) {
                        System.out.println("before");
                        System.err.println("warning");
                        fail();
                        System.out.println("unreachable");
                    }
                    static void fail() {
                        %s
                    }
                }
                """.formatted(statement);
        String goodSource = """
                public class Good {
                    public static void main(String[] args) {
                        System.out.println("after");
                    }
                }
                """;
        String input = request("failure", source) + "\n" + request("success", goodSource) + "\n";
        StringWriter output = new StringWriter();
        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    output);
        } // try
        String[] lines = output.toString().strip().split("\\R");
        assertThat(lines).hasSize(2);
        JsonObject result = result(lines[0]);
        assertThat(result.get("status").getAsString()).isEqualTo("failed");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("guest_exception");
        assertThat(result.get("complete").getAsBoolean()).isFalse();
        assertThat(result.get("stdout").getAsString()).isEqualTo("before\n");
        String stderr = result.get("stderr").getAsString();
        assertThat(stderr).startsWith("warning\nException in thread \"main\" " + exceptionClass)
                .contains("at Driver.fail(Driver.java:9)", "at Driver.main(Driver.java:5)")
                .doesNotContain("GuestHarness", "java.lang.reflect", "jdk.internal.reflect",
                        "student-main", "unreachable");
        if (exceptionClass.endsWith("NullPointerException")) {
            assertThat(stderr).contains("Cannot invoke", "node");
        } else if (exceptionClass.endsWith("IllegalStateException")) {
            assertThat(stderr).contains("student detail");
        } else {
            assertThat(stderr).contains("/ by zero");
        } // if
        assertThat(result.getAsJsonObject("counters").get("stderrBytes").getAsLong())
                .isEqualTo(stderr.getBytes(StandardCharsets.UTF_8).length);
        JsonArray snapshots = result.getAsJsonObject("trace").getAsJsonArray("trace");
        JsonObject last = snapshots.get(snapshots.size() - 1).getAsJsonObject();
        JsonObject before = snapshots.get(snapshots.size() - 2).getAsJsonObject();
        assertThat(last.get("line").getAsInt()).isEqualTo(9);
        assertThat(last.get("line")).isEqualTo(before.get("line"));
        assertThat(last.get("event").getAsString()).isEqualTo("step_line");
        assertThat(last.get("stderr").getAsString()).isEqualTo(stderr);
        assertThat(before.get("stderr").getAsString()).doesNotContain(exceptionClass);
        assertThat(last.get("stdout").getAsString()).isEqualTo("before\n");
        JsonObject good = result(lines[1]);
        assertThat(good.get("status").getAsString()).isEqualTo("completed");
        assertThat(good.get("stdout").getAsString()).isEqualTo("after\n");
        assertThat(good.get("stderr").getAsString()).isEmpty();
        assertThat(good.getAsJsonObject("trace").toString()).doesNotContain("warning", exceptionClass);
    } // uncaughtOutputAndRecovery

    @Test
    void caughtExceptionDoesNotTerminate() throws Exception {
        String source = """
                public class Driver {
                    public static void main(String[] args) {
                        try {
                            throw new IllegalStateException("caught");
                        } catch (IllegalStateException e) {
                            System.out.println("recovered");
                        }
                    }
                }
                """;
        StringWriter output = new StringWriter();
        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(new ByteArrayInputStream(
                    (request("caught", source) + "\n").getBytes(StandardCharsets.UTF_8)), output);
        } // try
        JsonObject result = result(output.toString());
        assertThat(result.get("status").getAsString()).isEqualTo("completed");
        assertThat(result.get("stdout").getAsString()).isEqualTo("recovered\n");
        assertThat(result.get("stderr").getAsString()).isEmpty();
    } // caughtExceptionDoesNotTerminate

    @Test
    void preservesCausesAndSuppressedExceptions() throws Exception {
        String source = """
                public class Driver {
                    public static void main(String[] args) {
                        RuntimeException cause = new RuntimeException("cause detail");
                        RuntimeException failure = new IllegalStateException("outer detail", cause);
                        failure.addSuppressed(new IllegalArgumentException("suppressed detail"));
                        throw failure;
                    }
                }
                """;
        StringWriter output = new StringWriter();
        try (BatchTraceService service = new BatchTraceService(1, 10)) {
            service.processStream(new ByteArrayInputStream(
                    (request("causes", source) + "\n").getBytes(StandardCharsets.UTF_8)), output);
        } // try
        JsonObject result = result(output.toString());
        assertThat(result.get("stopReason").getAsString()).isEqualTo("guest_exception");
        assertThat(result.get("stderr").getAsString())
                .contains("outer detail", "Caused by: java.lang.RuntimeException: cause detail",
                        "Suppressed: java.lang.IllegalArgumentException: suppressed detail",
                        "Driver.main(Driver.java:3)", "Driver.main(Driver.java:4)",
                        "Driver.main(Driver.java:5)")
                .doesNotContain("GuestHarness", "jdk.internal.reflect", "java.lang.reflect");
    } // preservesCausesAndSuppressedExceptions

    private static String request(String id, String source) {
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("source", source);
        request.addProperty("allBreakpoints", true);
        request.addProperty("format", "pytutor");
        return request.toString();
    } // request

    private static JsonObject result(String response) {
        return PyTutorSerializer.getGson(false).fromJson(response, JsonObject.class)
                .getAsJsonObject("result");
    } // result
} // BatchExceptionOutputTest
