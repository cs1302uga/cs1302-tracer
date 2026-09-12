package cs1302.tracer.execution;

import java.util.List;
import java.util.Map;

/**
 * Versioned opt-in job result. The trace payload retains its existing format.
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
 */
public record TraceResult(int schemaVersion, String format, String status, String stopReason,
        String phase, boolean complete, Object trace, TraceLimits limits,
        Map<String, Long> counters, List<String> diagnostics) {} // TraceResult
