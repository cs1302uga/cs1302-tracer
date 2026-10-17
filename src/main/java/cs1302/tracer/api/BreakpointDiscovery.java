package cs1302.tracer.api;

import org.apiguardian.api.API;

import java.util.List;

/**
 * Detached executable source locations and preparation diagnostics.
 * {@link Tracer#discoverBreakpoints(String)} produces locations in source-path and line order without
 * executing guest code, or an empty location list with diagnostics on preparation failure.
 * Direct construction preserves supplied order without sorting, deduplication, or result-consistency validation.
 *
 * @param breakpoints Discovered locations in their supplied order.
 * @param diagnostics Preparation diagnostics; discovery supplies an empty list on success.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record BreakpointDiscovery(List<BreakpointLocation> breakpoints, List<String> diagnostics) {

    /**
     * Takes shallow immutable copies of the locations and diagnostics.
     *
     * @throws NullPointerException If either list or any list entry is null.
     */
    public BreakpointDiscovery {
        breakpoints = List.copyOf(breakpoints);
        diagnostics = List.copyOf(diagnostics);
    } // BreakpointDiscovery

} // BreakpointDiscovery
