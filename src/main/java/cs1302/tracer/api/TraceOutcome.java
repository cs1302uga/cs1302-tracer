package cs1302.tracer.api;

import org.apiguardian.api.API;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Detached job outcome with already serialized JSON, valid after guest cleanup and reuse.
 *
 * @param format Selected output format.
 * @param status Completion, failure, or stopped status.
 * @param stopReason Machine-readable stop reason, or null on success.
 * @param phase Last execution phase.
 * @param complete Whether the job completed.
 * @param counters Resource accounting.
 * @param diagnostics Job diagnostics.
 * @param stdout Captured guest output.
 * @param stderr Captured guest error output.
 * @param traceJson Trace payload JSON, or the JSON literal null when unavailable.
 * @param json Complete result-envelope JSON.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record TraceOutcome(String format, String status, String stopReason, String phase, boolean complete,
        Map<String, Long> counters, List<String> diagnostics, String stdout, String stderr,
        String traceJson, String json) {

    /** Detaches resource counters and diagnostics from their producers. */
    public TraceOutcome {
        counters = Map.copyOf(counters);
        diagnostics = List.copyOf(diagnostics);
    } // TraceOutcome

    /**
     * Writes the result envelope without flushing or closing the caller's writer.
     *
     * @param destination Caller-owned destination.
     * @throws IOException If the destination cannot accept the JSON.
     */
    public void writeJson(Writer destination) throws IOException {
        destination.write(this.json);
    } // writeJson
} // TraceOutcome
