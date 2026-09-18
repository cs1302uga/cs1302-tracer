package cs1302.tracer.execution;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cs1302.tracer.trace.Snapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Emits complete captures on a bounded, separately framed checkpoint channel. */
public final class CheckpointStream {
    private final OutputStream output;
    private final Gson gson;
    private final Function<List<Snapshot>, Object> payload;
    private final String jobId;
    private final String stepsKey;
    private final long recordLimit;
    private final long totalLimit;
    private final Map<Snapshot, Long> identities = new IdentityHashMap<>();
    private final Map<String, byte[]> prefixes = new java.util.HashMap<>();
    private final Map<String, Long> generations = new java.util.HashMap<>();
    private long sequence;
    private long bytes;

    /**
     * Opens a checkpoint stream; stdout remains the ordinary result document.
     * @param output Side-channel destination.
     * @param gson Configured trace serializer.
     * @param payload Factory preserving the selected trace representation.
     * @param jobId Runner identity.
     * @param format Trace format.
     * @param capture Capture policy.
     * @param source Source bundle used by the worker.
     * @param recordLimit Maximum encoded record bytes.
     * @param totalLimit Maximum side-channel bytes.
     * @throws NoSuchAlgorithmException If SHA-256 is unavailable.
     */
    public CheckpointStream(OutputStream output, Gson gson,
            Function<List<Snapshot>, Object> payload, String jobId, String format,
            String capture, String source, long recordLimit, long totalLimit)
            throws NoSuchAlgorithmException {
        this.output = output;
        this.gson = gson;
        this.payload = payload;
        this.jobId = jobId;
        this.stepsKey = format.equals("modern") ? "steps" : "trace";
        this.recordLimit = recordLimit;
        this.totalLimit = totalLimit;
        JsonObject header = record("header");
        header.addProperty("format", format);
        header.addProperty("capture", capture);
        header.addProperty("encoding", "UTF-8");
        header.addProperty("sourceDigest", java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        source.getBytes(StandardCharsets.UTF_8))));
        JsonObject root = gson.toJsonTree(payload.apply(List.of())).getAsJsonObject();
        root.remove(stepsKey);
        header.add("root", root);
        emit(header);
    } // CheckpointStream

    /**
     * Publishes only the currently retained snapshot set, including replacements.
     * @param snapshots Atomic captures retained by the session.
     */
    public void publish(List<Snapshot> snapshots) {
        Map<Snapshot, Boolean> retained = new IdentityHashMap<>();
        snapshots.forEach(snapshot -> retained.put(snapshot, true));
        identities.keySet().retainAll(retained.keySet());
        JsonArray active = new JsonArray();
        for (Snapshot snapshot : snapshots) {
            Long identity = identities.get(snapshot);
            if (identity == null) {
                JsonObject root = gson.toJsonTree(
                        payload.apply(List.of(snapshot))).getAsJsonObject();
                JsonObject step = root.getAsJsonArray(stepsKey).get(0).getAsJsonObject();
                JsonObject references = new JsonObject();
                for (String stream : List.of("stdout", "stderr")) {
                    if (step.has(stream)) {
                        references.add(stream, append(stream, step.remove(stream).getAsString()));
                    } // if
                } // for
                JsonObject record = record("snapshot");
                record.add("step", step);
                record.add("output", references);
                identity = sequence;
                emit(record);
                identities.put(snapshot, identity);
            } // if
            active.add(identity);
        } // for
        JsonObject checkpoint = record("checkpoint");
        checkpoint.add("active", active);
        emit(checkpoint);
    } // publish

    /**
     * Appends output deltas, starting a generation if sanitization rewrote a prefix.
     * @param stream Stream identity.
     * @param text Complete decoded output prefix.
     * @return Reference to output bytes already emitted.
     */
    private JsonObject append(String stream, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        byte[] previous = prefixes.getOrDefault(stream, new byte[0]);
        long generation = generations.getOrDefault(stream, 0L);
        int start = previous.length;
        if (value.length < start || !Arrays.equals(previous, Arrays.copyOf(value, start))) {
            generation++;
            start = 0;
        } // if
        for (int offset = start; offset < Math.max(1, value.length); offset += 16384) {
            JsonObject chunk = record("output");
            chunk.addProperty("stream", stream);
            chunk.addProperty("generation", generation);
            chunk.addProperty("offset", offset);
            chunk.addProperty("data", Base64.getEncoder().encodeToString(
                    Arrays.copyOfRange(value, offset, Math.min(offset + 16384, value.length))));
            emit(chunk);
        } // for
        prefixes.put(stream, value);
        generations.put(stream, generation);
        JsonObject reference = new JsonObject();
        reference.addProperty("generation", generation);
        reference.addProperty("end", value.length);
        return reference;
    } // append

    /**
     * Begins the next strictly ordered protocol record.
     * @param kind Record kind.
     * @return Common metadata.
     */
    private JsonObject record(String kind) {
        JsonObject record = new JsonObject();
        record.addProperty("schemaVersion", 1);
        record.addProperty("jobId", jobId);
        record.addProperty("sequence", ++sequence);
        record.addProperty("kind", kind);
        return record;
    } // record

    /**
     * Emits a bounded complete record and flushes it to the supervising transport.
     * @param record Record to emit.
     */
    private void emit(JsonObject record) {
        byte[] encoded = ("\u001eTRACER1 " + gson.toJson(record) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > recordLimit || encoded.length > totalLimit - bytes) {
            throw new IllegalStateException("Checkpoint artifact limit exceeded");
        } // if
        try {
            output.write(encoded);
            output.flush();
            bytes += encoded.length;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } // try
    } // emit
} // CheckpointStream
