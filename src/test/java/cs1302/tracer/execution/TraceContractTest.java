package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TraceContractTest {
    @Test
    void rejectsInvalidBudgets() {
        assertThatThrownBy(() -> new TraceLimits(-1, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceLimits(Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new JobOptions().limits()).isEqualTo(TraceLimits.unlimited());
    }

    @Test
    void envelopePreservesPayloadAndDistinguishesUnavailableFromEmpty() {
        Gson gson = new GsonBuilder().serializeNulls().create();
        JsonObject payload = JsonParser.parseString("{\"code\":\"\",\"trace\":[]}").getAsJsonObject();
        for (String reason : List.of("timeout", "cancelled", "compile_error", "tracer_error")) {
            var result = new TraceResult(1, "pytutor", "stopped", reason, "trace", false,
                    payload, TraceLimits.unlimited(), Map.of("snapshots", 0L), List.of());
            var json = gson.toJsonTree(result).getAsJsonObject();
            assertThat(json.get("trace")).isEqualTo(payload);
            assertThat(json.get("complete").getAsBoolean()).isFalse();
            var unavailable = new TraceResult(1, "pytutor", "failed", reason, "compile", false,
                    null, TraceLimits.unlimited(), Map.of(), List.of());
            assertThat(gson.toJsonTree(unavailable).getAsJsonObject().get("trace").isJsonNull()).isTrue();
        }
    }
}
