package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * Guest inspection behavior; neither policy provides process isolation.
 * Multithread capture always uses {@link #FIELDS}, even when a request selects {@link #TRUSTED}.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum InspectionPolicy {

    /**
     * Uses specialized collection, map, wrapper, and stream inspection that can invoke guest methods.
     * Such calls can mutate guest state or block; finite tracing budgets are recommended.
     */
    TRUSTED,

    /**
     * Inspects ordinary objects through fields without invoking guest methods for inspection.
     * Arrays, strings, and {@code java.awt.Color} retain dedicated representations. Source discovery uses only
     * submitted sources, and guest buffers are not flushed through method invocation.
     */
    FIELDS

} // InspectionPolicy
