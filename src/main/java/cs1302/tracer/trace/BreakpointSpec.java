package cs1302.tracer.trace;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ReferenceType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Specification for a breakpoint target, which may be unqualified (line number only)
 * or file-qualified (relative source file or class name with line number).
 *
 * @param file The optional source file name or relative path.
 * @param lineNumber The 1-based source line number, or -1 for end of main execution.
 */
public record BreakpointSpec(Optional<String> file, int lineNumber) {

    /**
     * Constructs a validated BreakpointSpec.
     *
     * @param file Optional file qualification.
     * @param lineNumber Line number (1-based or -1).
     */
    public BreakpointSpec {
        Objects.requireNonNull(file, "file cannot be null");
    } // BreakpointSpec

    /**
     * Creates an unqualified breakpoint specification for a line number.
     *
     * @param lineNumber Line number.
     * @return BreakpointSpec instance.
     */
    public static BreakpointSpec of(int lineNumber) {
        return new BreakpointSpec(Optional.empty(), lineNumber);
    } // of

    /**
     * Creates a file-qualified breakpoint specification.
     *
     * @param file Source file name or relative path.
     * @param lineNumber Line number.
     * @return BreakpointSpec instance.
     */
    public static BreakpointSpec of(String file, int lineNumber) {
        return new BreakpointSpec(
                Optional.ofNullable(file).filter(f -> !f.isBlank()),
                lineNumber);
    } // of

    /**
     * Parses a breakpoint string into a BreakpointSpec.
     *
     * <p>Supported formats include:
     * <ul>
     *   <li>{@code "12"} - unqualified line number 12</li>
     *   <li>{@code "Foo.java:12"} - line 12 in Foo.java</li>
     *   <li>{@code "pkg/Foo.java:12"} - line 12 in pkg/Foo.java</li>
     *   <li>{@code "-1"} - end of main execution</li>
     * </ul>
     *
     * @param spec The raw specification string.
     * @return Parsed BreakpointSpec.
     * @throws IllegalArgumentException If syntax is invalid or line number cannot be parsed.
     */
    public static BreakpointSpec parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Breakpoint specification cannot be null or blank");
        } // if
        String trimmed = spec.trim();
        int firstColon = trimmed.indexOf(':');
        int colon = trimmed.lastIndexOf(':');
        if (firstColon != colon) {
            throw new IllegalArgumentException(
                    "Multiple colons in breakpoint specification: " + trimmed);
        } // if
        if (colon == -1) {
            try {
                return of(Integer.parseInt(trimmed));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                        "Invalid breakpoint line number: " + trimmed, nfe);
            } // try
        } // if

        String filePart = trimmed.substring(0, colon).trim();
        String linePart = trimmed.substring(colon + 1).trim();
        if (filePart.isEmpty()) {
            throw new IllegalArgumentException(
                    "Breakpoint file part cannot be empty in: " + trimmed);
        } // if
        try {
            int line = Integer.parseInt(linePart);
            return of(filePart, line);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Invalid breakpoint line number in: " + trimmed, nfe);
        } // try
    } // parse

    /**
     * Checks if this breakpoint specification matches a given source file path.
     *
     * @param sourcePath Relative source file path.
     * @return True if this specification matches the given path.
     */
    public boolean matchesSourcePath(String sourcePath) {
        if (file.isEmpty()) {
            return true;
        } // if
        if (sourcePath == null || sourcePath.isBlank()) {
            return false;
        } // if
        String filter = file.get().replace('\\', '/');
        String normalized = sourcePath.replace('\\', '/');
        if (normalized.equals(filter) || normalized.endsWith("/" + filter)) {
            return true;
        } // if
        String nameWithoutExt = filter.endsWith(".java")
                ? filter.substring(0, filter.length() - 5) : filter;
        String sourceWithoutExt = normalized.endsWith(".java")
                ? normalized.substring(0, normalized.length() - 5) : normalized;
        return sourceWithoutExt.equals(nameWithoutExt)
                || sourceWithoutExt.endsWith("/" + nameWithoutExt)
                || sourceWithoutExt.endsWith("." + nameWithoutExt);
    } // matchesSourcePath

    /**
     * Checks if this breakpoint specification matches a JDI reference type.
     *
     * @param refType The JDI ReferenceType to inspect.
     * @return True if this specification applies to the reference type.
     */
    public boolean matchesReferenceType(ReferenceType refType) {
        if (file.isEmpty()) {
            return true;
        } // if
        if (refType == null) {
            return false;
        } // if
        String typeName = refType.name();
        if (matchesSourcePath(typeName)) {
            return true;
        } // if

        try {
            String sourceName = refType.sourceName();
            if (sourceName != null && matchesSourcePath(sourceName)) {
                return true;
            } // if
        } catch (AbsentInformationException ignored) {
            // debug info absent
        } // try

        try {
            List<String> paths = refType.sourcePaths(null);
            if (paths != null) {
                for (String p : paths) {
                    if (matchesSourcePath(p)) {
                        return true;
                    } // if
                } // for
            } // if
        } catch (AbsentInformationException ignored) {
            // debug info absent
        } // try

        return false;
    } // matchesReferenceType

    /**
     * Returns a human-readable representation of this breakpoint spec.
     *
     * @return Formatted breakpoint string.
     */
    @Override
    public String toString() {
        return file.map(f -> f + ":" + lineNumber).orElseGet(() -> String.valueOf(lineNumber));
    } // toString
} // BreakpointSpec
