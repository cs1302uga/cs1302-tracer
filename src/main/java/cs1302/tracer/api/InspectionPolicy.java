package cs1302.tracer.api;

import org.apiguardian.api.API;

/** Guest inspection behavior; neither policy provides process isolation. */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum InspectionPolicy {
    TRUSTED, FIELDS
} // InspectionPolicy
