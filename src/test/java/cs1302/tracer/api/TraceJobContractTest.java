package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Characterizes caller-visible validation order before job resolution changes. */
class TraceJobContractTest {
    private static final String PROGRAM = "public class Main { public static void main(String[] args) {} }";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void javaCompilesBeforeValidatingBreakpoints(boolean validSource) {
        TraceOutcome result = Tracer.trace(new TraceRequest(validSource ? PROGRAM : "not Java", "",
                TraceFormat.MODERN, List.of("bad"), false, false, false, false, false, false, TypeStyle.FQN,
                TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED));
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.phase()).isEqualTo(validSource ? "trace" : "compile");
        assertThat(result.stopReason()).isEqualTo(validSource ? "tracer_error" : "compile_error");
        assertThat(result.stdout()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchCompilesBeforeValidatingBreakpoints(boolean validSource) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("id", "precedence");
        request.addProperty("source", validSource ? PROGRAM : "not Java");
        request.addProperty("format", "modern");
        request.add("breakpoints", JsonParser.parseString("[\"bad\"]"));
        StringWriter output = new StringWriter();
        try (BatchTracer tracer = new BatchTracer(1, 2, 1, false)) {
            tracer.processStream(new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)), output);
        }
        JsonObject response = JsonParser.parseString(output.toString()).getAsJsonObject();
        assertThat(response.get("id").getAsString()).isEqualTo("precedence");
        JsonObject result = response.getAsJsonObject("result");
        assertThat(result.get("status").getAsString()).isEqualTo("failed");
        assertThat(result.get("phase").getAsString()).isEqualTo(validSource ? "trace" : "compile");
        assertThat(result.get("stopReason").getAsString()).isEqualTo(validSource ? "tracer_error" : "compile_error");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cliRejectsBreakpointsBeforeOpeningMissingSource(boolean envelope, @TempDir Path root) throws Exception {
        Path output = root.resolve("out.txt");
        Path errors = root.resolve("err.txt");
        java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), "cs1302.tracer.App", "trace", "--format", "modern",
                "--breakpoints", "bad", "--input", root.resolve("missing.java").toString()));
        if (envelope) {
            args.add("--result-envelope");
        }
        Process process = new ProcessBuilder(args).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(2);
            assertThat(Files.readString(output)).isEmpty();
            assertThat(Files.readString(errors)).contains("bad").doesNotContain("NoSuchFileException");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }
}
