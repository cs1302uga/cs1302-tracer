package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.internal.batch.BatchJobRequest;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.trace.BreakpointSpec;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Verifies construction compatibility and caller-visible policy and validation order. */
class TraceJobContractTest {
    private static final String PROGRAM = "public class Main { public static void main(String[] args) {} }";

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void javaCompilesBeforeValidatingBreakpoints(boolean validSource, boolean useBuilder) {
        String source = validSource ? PROGRAM : "not Java";
        TraceRequest constructed = new TraceRequest(source, "",
                TraceFormat.MODERN, List.of("bad"), false, false, false, false, false, false, TypeStyle.FQN,
                TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED);
        TraceRequest built = TraceRequest.builder(source).format(TraceFormat.MODERN)
                .breakpoints(List.of("bad")).build();
        assertThat(built).isEqualTo(constructed);
        TraceOutcome result = Tracer.trace(useBuilder ? built : constructed);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.phase()).isEqualTo(validSource ? "trace" : "compile");
        assertThat(result.stopReason()).isEqualTo(validSource ? "tracer_error" : "compile_error");
        assertThat(result.stdout()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void batchCompilesBeforeValidatingBreakpoints(boolean validSource, boolean useBuilder) throws Exception {
        String source = validSource ? PROGRAM : "not Java";
        BatchJobRequest constructed = new BatchJobRequest("precedence", source, "modern", null, List.of("bad"),
                null, null, null, null, null, null, null, null, null, null);
        BatchJobRequest built = BatchJobRequest.builder().id("precedence").source(source).format("modern")
                .breakpoints(List.of("bad")).build();
        assertThat(built).isEqualTo(constructed);
        String request = new Gson().toJson(useBuilder ? built : constructed);
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
    @EnumSource(TraceFormat.class)
    void constructorAndBuilderProduceEquivalentTracesAndEnvelopes(TraceFormat format) {
        String source = """
                public class Main {
                    static int answer;
                    public static void main(String[] args) {
                        args = null;
                        int value = 21;
                        answer = value * 2;
                        System.out.print(answer);
                    }
                }
                """;
        TraceLimits limits = TraceLimits.instructorDefaults().toBuilder().snapshots(100).build();
        TraceRequest constructed = new TraceRequest(source, "unused\n", format, List.of(), false, false, false,
                true, true, true, TypeStyle.SIMPLE, limits, InspectionPolicy.FIELDS);
        TraceRequest built = TraceRequest.builder(source).stdin("unused\n").format(format).removeMainArgs(true)
                .inlineStrings(true).removeMethodThis(true).typeStyle(TypeStyle.SIMPLE).limits(limits)
                .inspection(InspectionPolicy.FIELDS).build();
        TraceOutcome expected = Tracer.trace(constructed, true);
        TraceOutcome actual = Tracer.trace(built, true);
        assertThat(expected.complete()).as(expected.json()).isTrue();
        assertThat(actual.complete()).as(actual.json()).isTrue();
        assertThat(actual.stdout()).isEqualTo("42");
        JsonObject expectedTrace = JsonParser.parseString(expected.traceJson()).getAsJsonObject();
        JsonObject actualTrace = JsonParser.parseString(actual.traceJson()).getAsJsonObject();
        JsonArray steps = actualTrace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getAsJsonObject().getAsJsonObject("heap").size()).isZero();
        assertThat(actual.traceJson()).contains("\"binary\"");
        assertThat(actualTrace).isEqualTo(expectedTrace);
        JsonObject expectedEnvelope = JsonParser.parseString(expected.json()).getAsJsonObject();
        JsonObject actualEnvelope = JsonParser.parseString(actual.json()).getAsJsonObject();
        expectedEnvelope.remove("counters");
        actualEnvelope.remove("counters");
        assertThat(actualEnvelope).isEqualTo(expectedEnvelope);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multithreadResolutionPreservesRequestedChronologyAndInspection(boolean useBuilder) {
        TraceRequest constructed = new TraceRequest(PROGRAM, "", TraceFormat.MODERN, List.of(), false, false,
                true, false, false, false, TypeStyle.FQN, TraceLimits.instructorDefaults(), InspectionPolicy.TRUSTED);
        TraceRequest built = TraceRequest.builder(PROGRAM).format(TraceFormat.MODERN).multithread(true).build();
        TraceRequest requested = useBuilder ? built : constructed;
        TraceJob effective = TraceJob.fromJava(requested, false);
        assertThat(effective.inspection()).isEqualTo(InspectionPolicy.FIELDS);
        assertThat(effective.capture().chronological()).isTrue();
        assertThat(effective.capture().selection().breakpoints()).isNull();
        assertThat(requested).isEqualTo(constructed);
        assertThat(requested.chronological()).isFalse();
        assertThat(requested.inspection()).isEqualTo(InspectionPolicy.TRUSTED);
        assertThat(requested.toBuilder().build()).isEqualTo(constructed);
    }

    @Test
    void batchBuilderLeavesAcceptedNullAndBlankSelectionsForJobResolution() {
        BatchJobRequest request = BatchJobRequest.builder().source(PROGRAM).allBreakpoints(true)
                .breakpoints(Arrays.asList(null, " ", "12, , 13")).build();
        TraceJob resolved = TraceJob.resolve(TraceJob.Profile.NDJSON, request.resolveFormat(),
                request.resolveTypeStyle(), request.resolveLimits(), request.inspection(), request.breakpoints(),
                Boolean.TRUE.equals(request.allBreakpoints()), false, false, false, false, false, true);
        assertThat(resolved.capture().selection().breakpoints())
                .containsExactly(BreakpointSpec.of(12), BreakpointSpec.of(13));
        assertThat(resolved.capture().chronological()).isTrue();
        assertThat(request.breakpoints()).containsExactly(null, " ", "12, , 13");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchBuilderPreservesOmittedAndEmptySelectionsWhileResolutionChoosesAllLines(boolean omitted) {
        BatchJobRequest.Builder builder = BatchJobRequest.builder().source(PROGRAM).allBreakpoints(true);
        if (!omitted) {
            builder.breakpoints(List.of());
        }
        BatchJobRequest request = builder.build();
        TraceJob resolved = TraceJob.resolve(TraceJob.Profile.NDJSON, request.resolveFormat(),
                request.resolveTypeStyle(), request.resolveLimits(), request.inspection(), request.breakpoints(),
                Boolean.TRUE.equals(request.allBreakpoints()), false, false, false, false, false, true);
        assertThat(request.breakpoints()).isEqualTo(omitted ? null : List.of());
        assertThat(request.limits()).isNull();
        assertThat(resolved.capture().selection().breakpoints()).isNull();
        assertThat(resolved.capture().chronological()).isTrue();
        assertThat(resolved.limits()).isEqualTo(TraceLimits.unlimited());
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
