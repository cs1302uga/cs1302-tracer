package cs1302.tracer.internal.serialize;

import org.apiguardian.api.API;

import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.internal.execution.TraceResult;

/** Freezes internal wire models into dependency-free public outcomes. */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class TraceOutcomes {
    /** Prevents utility construction. */
    private TraceOutcomes() {} // TraceOutcomes

    /**
     * Serializes the payload and envelope before their producing job is released.
     *
     * @param result Internal result model.
     * @param pretty Whether to indent JSON.
     * @return Detached public result.
     */
    public static TraceOutcome freeze(TraceResult result, boolean pretty) {
        return new TraceOutcome(result.format(), result.status(), result.stopReason(), result.phase(),
                result.complete(), result.counters(), result.diagnostics(), result.stdout(), result.stderr(),
                PyTutorSerializer.getGson(pretty).toJson(result.trace()),
                PyTutorSerializer.getGson(pretty).toJson(result));
    } // freeze
} // TraceOutcomes
