package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TraceContractTest {
    @Test
    void finalOutputRefreshPreservesLogicalInput() {
        try (TraceSession session = new TraceSession(
                TraceLimits.unlimited(), InspectionPolicy.FIELDS, true);
                var err = new cs1302.tracer.trace.StreamDrainer(
                        new java.io.ByteArrayInputStream(new byte[0]));
                var out = new cs1302.tracer.trace.StreamDrainer(
                        new java.io.ByteArrayInputStream(new byte[] {65}))) {
            err.waitForEof(1000);
            out.waitForEof(1000);
            session.beginSnapshot();
            session.commit(new cs1302.tracer.trace.ExecutionSnapshot(
                    List.of(), List.of(), java.util.Map.of(), new byte[0], new byte[0],
                    java.util.Optional.empty(), "hello", 5));
            session.finishOutput();
            assertThat(session.snapshots().getFirst().stdinConsumed()).isEqualTo("hello");
            assertThat(session.snapshots().getFirst().stdinOffset()).isEqualTo(5);
            assertThat(session.snapshots().getFirst().stdout()).containsExactly((byte) 65);
        }
    }

    @Test
    void explicitZeroAndUnlimitedOverrideInstructorDefaults() {
        JobOptions options = new JobOptions();
        new picocli.CommandLine(options).parseArgs("--timeout-ms", "0");
        assertThat(options.limits().timeoutMillis()).isZero();
        assertThat(options.limits().outputBytes()).isPositive();
        options = new JobOptions();
        new picocli.CommandLine(options).parseArgs("--unlimited", "--max-snapshots", "3");
        assertThat(options.limits().timeoutMillis()).isZero();
        assertThat(options.limits().snapshots()).isEqualTo(3);
        options = new JobOptions();
        new picocli.CommandLine(options).parseArgs("--result-envelope");
        assertThat(options.limits()).isEqualTo(TraceLimits.unlimited());
    }

    @Test
    void rejectsInvalidBudgets() {
        assertThatThrownBy(() -> new TraceLimits(-1, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceLimits(Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new JobOptions().limits()).isEqualTo(TraceLimits.instructorDefaults());
    }

    @Test
    void envelopePreservesPayloadAndDistinguishesUnavailableFromEmpty() {
        Gson gson = new GsonBuilder().serializeNulls().create();
        JsonObject payload = JsonParser.parseString("{\"code\":\"\",\"trace\":[]}").getAsJsonObject();
        for (String reason : List.of("timeout", "cancelled", "compile_error", "tracer_error")) {
            var result = new TraceResult(1, "pytutor", "stopped", reason, "trace", false,
                    payload, TraceLimits.unlimited(), Map.of("snapshots", 0L), List.of(), "", "");
            var json = gson.toJsonTree(result).getAsJsonObject();
            assertThat(json.get("trace")).isEqualTo(payload);
            assertThat(json.get("complete").getAsBoolean()).isFalse();
            var unavailable = new TraceResult(1, "pytutor", "failed", reason, "compile", false,
                    null, TraceLimits.unlimited(), Map.of(), List.of(), "", "");
            assertThat(gson.toJsonTree(unavailable).getAsJsonObject().get("trace").isJsonNull()).isTrue();
        }
    }

    @Test
    void testEvalEnumHashSettings() throws Exception {
        JobOptions job1 = new JobOptions();
        assertThat(job1.evalEnumHash).isTrue();
        new picocli.CommandLine(job1).parseArgs("--no-eval-enum-hash");
        assertThat(job1.evalEnumHash).isFalse();

        JobOptions job2 = new JobOptions();
        new picocli.CommandLine(job2).parseArgs("--eval-enum-hash");
        assertThat(job2.evalEnumHash).isTrue();
        assertThat(TraceSession.shouldEvalEnumHash()).isTrue();

        try (AutoCloseable scope = TraceSession.withEvalEnumHash(false)) {
            assertThat(scope).isNotNull();
            assertThat(TraceSession.shouldEvalEnumHash()).isFalse();
        }
        assertThat(TraceSession.shouldEvalEnumHash()).isTrue();

        try (TraceSession session = new TraceSession(
                TraceLimits.unlimited(), InspectionPolicy.TRUSTED, true, false)) {
            assertThat(session).isNotNull();
            assertThat(TraceSession.shouldEvalEnumHash()).isFalse();
        }
        assertThat(TraceSession.shouldEvalEnumHash()).isTrue();

        try (TraceSession session = new TraceSession(
                TraceLimits.unlimited(), InspectionPolicy.FIELDS, true, true)) {
            assertThat(session).isNotNull();
            assertThat(TraceSession.shouldEvalEnumHash()).isFalse();
        }
        assertThat(TraceSession.shouldEvalEnumHash()).isTrue();
    }
}
