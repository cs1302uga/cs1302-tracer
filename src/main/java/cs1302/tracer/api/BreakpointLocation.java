package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * An executable line in a submitted source file.
 *
 * @param sourcePath Package-relative source path using forward slashes.
 * @param line One-based executable source line.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record BreakpointLocation(String sourcePath, int line) {} // BreakpointLocation
