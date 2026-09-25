package cs1302.tracer.batch;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.Gson;
import cs1302.tracer.execution.TraceResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JsonNestingTest {
    @Test
    void countsContainersButNotEscapedStringContents() throws Exception {
        for (int depth : List.of(63, 64)) {
            JsonNesting.validate("[".repeat(depth) + "0" + "]".repeat(depth));
        }
        JsonNesting.validate("{\"a\":[true,false,null,1.5,\"[\\\"{\\\\\"]}");
        assertThatThrownBy(() -> JsonNesting.validate("[".repeat(65) + "0" + "]".repeat(65)))
                .hasMessage("json_nesting_limit");
        assertThatThrownBy(() -> JsonNesting.validate("{\"x\":[}"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void oversizedUnknownPropertyDoesNotPreventNextBatchJob() throws Exception {
        String deep = "{\"id\":\"bad\",\"ignored\":" + "[".repeat(10000) + "0"
                + "]".repeat(10000) + "}";
        String input = deep + "\n{\"id\":\"good\"}\n";
        var output = new StringWriter();
        var calls = new AtomicInteger();
        try (var service = new BatchTraceService(1, 10, 1, false, request -> {
            calls.incrementAndGet();
            return new BatchJobResponse(request.id(), TraceResult.failed("modern", "test", "runner reached"));
        })) {
            service.processStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        }
        String[] lines = output.toString().split("\\R");
        assertThat(lines).hasSize(2);
        var first = new Gson().fromJson(lines[0], BatchJobResponse.class);
        assertThat(first.id()).isNull();
        assertThat(first.result().stopReason()).isEqualTo("json_nesting_limit");
        assertThat(first.result().phase()).isEqualTo("parse");
        assertThat(new Gson().fromJson(lines[1], BatchJobResponse.class).id()).isEqualTo("good");
        assertThat(calls).hasValue(1);
    }
}
