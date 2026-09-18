package cs1302.tracer.trace;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Parses and matches exact source-relative breakpoint locations. */
public final class BreakpointSelection {
    private final Set<Location> locations;

    /**
     * Creates an immutable selection from CLI selectors.
     * @param selectors Source-relative path and positive line, separated by a colon.
     */
    public BreakpointSelection(List<String> selectors) {
        locations = selectors.stream().map(BreakpointSelection::parse).collect(Collectors.toSet());
    } // BreakpointSelection

    /**
     * Parses one qualified selector.
     * @param selector Path and line.
     * @return Normalized location.
     */
    private static Location parse(String selector) {
        int colon = selector.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Expected source-relative path:line: " + selector);
        } // if
        String path = selector.substring(0, colon).replace('\\', '/');
        int line = Integer.parseInt(selector.substring(colon + 1));
        if (line <= 0 || path.startsWith("/") || path.contains(":")
                || List.of(path.split("/")).contains("..")) {
            throw new IllegalArgumentException("Invalid qualified breakpoint: " + selector);
        } // if
        return new Location(Path.of(path).normalize().toString().replace('\\', '/'), line);
    } // parse

    /**
     * Validates selectors against compiler-produced source and line identities.
     * @param available Executable lines grouped by exact source-relative path.
     */
    public void validate(Map<String, Set<Integer>> available) {
        for (Location location : locations) {
            Set<Integer> lines = available.getOrDefault(location.sourcePath(), Set.of());
            if (!lines.contains(location.line())) {
                throw new IllegalArgumentException("No executable breakpoint at "
                        + location.sourcePath() + ":" + location.line());
            } // if
        } // for
    } // validate

    /**
     * Reports whether qualified selection is active.
     * @return True if at least one location was selected.
     */
    public boolean qualified() {
        return !locations.isEmpty();
    } // qualified

    /**
     * Returns distinct line numbers needed for JDI location discovery.
     * @return Sorted selected lines.
     */
    public Collection<Integer> lines() {
        return locations.stream().map(Location::line)
                .collect(Collectors.toCollection(TreeSet::new));
    } // lines

    /**
     * Matches a JDI location against exact selected source identities.
     * @param sourcePath Source-relative path from debug metadata.
     * @param line Executable line.
     * @return Whether the location is selected.
     */
    public boolean matches(String sourcePath, int line) {
        return locations.contains(new Location(sourcePath.replace('\\', '/'), line));
    } // matches

    /**
     * Identity used by qualified matching and snapshot retention.
     * @param sourcePath Source-relative path; empty denotes legacy line-only retention.
     * @param line Executable line.
     */
    public record Location(String sourcePath, int line) {} // Location
} // BreakpointSelection
