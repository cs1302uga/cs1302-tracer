package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Guest inspection behavior; neither policy provides process isolation.
 * {@link TraceRequest#TraceRequest(String)} defaults to {@link #TRUSTED}. These policies control
 * the tracer's observation of guest state; they do not restrict operations performed by guest code.
 * Multithread capture always uses {@link #FIELDS}, even when a request selects {@link #TRUSTED}.
 *
 * @see TraceRequest#inspection()
 * @see TraceLimits
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum InspectionPolicy {

    /**
     * Uses specialized collection, map, wrapper, and stream inspection that can invoke guest methods.
     * Such calls can mutate guest state or block; finite tracing budgets are recommended.
     * Supported {@code java.time} value objects expose selected stored components without guest calls.
     * Source preparation may resolve supporting source or binary type information under the
     * calling entry point's discovery policy.
     */
    TRUSTED,

    /**
     * Inspects ordinary objects through fields without invoking guest methods for inspection.
     * Arrays, strings, and {@code java.awt.Color} retain dedicated representations. Source discovery uses only
     * submitted sources, and guest buffers are not flushed through method invocation.
     * Collection implementation fields can therefore appear instead of the specialized logical
     * contents produced by {@link #TRUSTED} inspection.
     */
    FIELDS

} // InspectionPolicy
