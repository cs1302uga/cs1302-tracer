package cs1302.tracer.execution;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Versioned opt-in job result. The trace payload retains its existing format.
 *
 * @param schemaVersion Envelope schema version.
 * @param format Payload format name.
 * @param status completed, stopped, or failed.
 * @param stopReason Machine-readable reason, or null on normal completion.
 * @param phase Phase at completion or failure.
 * @param complete Whether execution and capture completed normally.
 * @param trace Payload; null when unavailable, empty steps when capture ran without snapshots.
 * @param limits Effective trace policy.
 * @param counters Resource accounting and snapshot counters.
 * @param diagnostics Human-readable diagnostics; never an isolation attestation.
 * @param stdout Bounded guest standard output, decoded as UTF-8.
 * @param stderr Bounded guest standard error, decoded as UTF-8.
 */
public record TraceResult(int schemaVersion, String format, String status, String stopReason,
        String phase, boolean complete, Object trace, TraceLimits limits,
        Map<String, Long> counters, List<String> diagnostics,
        String stdout, String stderr) {

    /**
     * Creates a failed TraceResult with specified failure details.
     *
     * @param format Output format.
     * @param phase Failure phase.
     * @param diagnostic Human-readable diagnostic.
     * @return New failed TraceResult instance.
     */
    public static TraceResult failed(String format, String phase, String diagnostic) {
        return new TraceResult(1, format, "failed", phase + "_error", phase, false,
                null, TraceLimits.unlimited(), Collections.emptyMap(),
                List.of(diagnostic), "", "");
    } // failed

    /**
     * Creates a stopped TraceResult with specified stop reason.
     *
     * @param format Output format.
     * @param reason Machine-readable reason.
     * @param diagnostic Human-readable diagnostic.
     * @return New stopped TraceResult instance.
     */
    public static TraceResult stopped(String format, String reason, String diagnostic) {
        return new TraceResult(1, format, "stopped", reason, "trace", false,
                null, TraceLimits.unlimited(), Collections.emptyMap(),
                List.of(diagnostic), "", "");
    } // stopped
} // TraceResult
