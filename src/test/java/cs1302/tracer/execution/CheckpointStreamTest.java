package cs1302.tracer.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.Snapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CheckpointStreamTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Snapshot snapshot(String output) {
        return new ExecutionSnapshot(List.of(), List.of(), Map.of(),
                output.getBytes(StandardCharsets.UTF_8), new byte[0], Optional.empty(), "", 0);
    }

    private JsonObject payload(List<Snapshot> snapshots, String key, boolean stderr) {
        JsonObject root = new JsonObject();
        JsonArray steps = new JsonArray();
        for (Snapshot snapshot : snapshots) {
            JsonObject step = new JsonObject();
            step.addProperty("stdout", new String(snapshot.stdout(), StandardCharsets.UTF_8));
            if (stderr) step.addProperty("stderr", "");
            steps.add(step);
        }
        root.add(key, steps);
        return root;
    }

    @Test void outputDeltasGenerationsRetentionAndBothFormats() throws Exception {
        for (String format : List.of("modern", "pytutor")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            String key = format.equals("modern") ? "steps" : "trace";
            CheckpointStream stream = new CheckpointStream(bytes, GSON,
                    states -> payload(states, key, format.equals("modern")),
                    "test", format, "latest", "source", 100000, 1000000);
            Snapshot first = snapshot("abc");
            stream.publish(List.of(first));
            stream.publish(List.of(first, snapshot("abcdef")));
            stream.publish(List.of(snapshot("xyz")));
            stream.publish(List.of(snapshot("ABC")));
            stream.publish(List.of(snapshot("ABC")));
            stream.publish(List.of(snapshot("x")));
            stream.publish(List.of(snapshot("")));
            stream.publish(List.of(snapshot("x".repeat(20000))));
            List<JsonObject> records = bytes.toString(StandardCharsets.UTF_8).lines()
                    .map(line -> JsonParser.parseString(line.substring(9)).getAsJsonObject()).toList();
            for (int i = 0; i < records.size(); i++) {
                assertEquals(i + 1, records.get(i).get("sequence").getAsInt());
            }
            assertEquals("header", records.getFirst().get("kind").getAsString());
            assertEquals(1, records.getLast().getAsJsonArray("active").size());
            assertTrue(records.stream().anyMatch(record -> record.has("generation")
                    && record.get("generation").getAsInt() > 0));
        }
    }

    @Test void limitsAndBrokenDestinationFailExplicitly() throws Exception {
        assertThrows(IllegalStateException.class, () -> new CheckpointStream(
                new ByteArrayOutputStream(), GSON, states -> payload(states, "steps", true),
                "test", "modern", "latest", "", 1, 100000));
        assertThrows(IllegalStateException.class, () -> new CheckpointStream(
                new ByteArrayOutputStream(), GSON, states -> payload(states, "steps", true),
                "test", "modern", "latest", "", 100000, 1));
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        };
        assertThrows(UncheckedIOException.class, () -> new CheckpointStream(
                broken, GSON, states -> payload(states, "steps", true),
                "test", "modern", "latest", "", 100000, 100000));
    }
}
