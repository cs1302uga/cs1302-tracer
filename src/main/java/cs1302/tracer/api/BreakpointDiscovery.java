package cs1302.tracer.api;

import org.apiguardian.api.API;

import java.util.List;

/**
 * Executable source locations, or diagnostics when preparation fails; guest code is never executed.
 *
 * @param breakpoints Discovered locations in source-path and line order.
 * @param diagnostics Preparation diagnostics; empty on success.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record BreakpointDiscovery(List<BreakpointLocation> breakpoints, List<String> diagnostics) {
    /** Detaches locations and diagnostics from their producers. */
    public BreakpointDiscovery {
        breakpoints = List.copyOf(breakpoints);
        diagnostics = List.copyOf(diagnostics);
    } // BreakpointDiscovery
} // BreakpointDiscovery
