package cs1302.tracer.api;

import org.apiguardian.api.API;

/**
 * An executable line in a submitted source file.
 * Locations returned by {@link Tracer#discoverBreakpoints(String)} follow the documented path and line
 * conventions. Direct construction stores the supplied values without validating the path or line number.
 *
 * <p>The debug source path combines the declared package and source filename, such as
 * {@code demo/Main.java}; it need not equal a {@link SourceBundle} logical path such as
 * {@code src/demo/Main.java}. A discovered location can qualify a requested breakpoint as
 * {@code sourcePath + ":" + line}, for example {@code demo/Main.java:12}.
 *
 * @param sourcePath Package-relative debug source path using forward slashes when returned by discovery.
 * @param line One-based executable source line when returned by discovery; it is not a snapshot index.
 * @see TraceRequest#breakpoints()
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public record BreakpointLocation(String sourcePath, int line) {} // BreakpointLocation
