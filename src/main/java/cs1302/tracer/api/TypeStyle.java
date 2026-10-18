package cs1302.tracer.api;

import org.apiguardian.api.API;

import java.util.regex.Pattern;

/**
 * Represents the type qualification style used when rendering types in stack frames, object labels, variable
 * attributes, and reconstructed lambda return and parameter types. Lambda bodies retain their captured text.
 * {@link TraceRequest#TraceRequest(String)} defaults to {@link #FQN}. This choice changes label
 * presentation without changing captured object identity, source names, or source discovery.
 *
 * @see TraceRequest#typeStyle()
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public enum TypeStyle {

    /**
     * Preserves captured type-name text, including package qualification when available
     * (e.g., {@code java.lang.String}, {@code pkg.Pair<java.lang.String, java.lang.Integer>}).
     */
    FQN("fqn"),

    /**
     * Simplified type names without dotted identifier prefixes
     * (e.g., {@code String}, {@code Pair<String, Integer>}).
     * Distinct qualified names can therefore have the same displayed label.
     */
    SIMPLE("simple");

    private static final Pattern PACKAGE_PREFIX_PATTERN = Pattern.compile("\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\.");

    private final String value;

    /**
     * Constructs a TypeStyle with the specified string value.
     *
     * @param value The string identifier.
     */
    TypeStyle(String value) {
        this.value = value;
    } // TypeStyle

    /**
     * Formats the given type name according to this type style.
     * {@link #FQN} returns the input unchanged; {@link #SIMPLE} applies {@link #simplify(String)}.
     *
     * @param typeName The raw type name string (may be fully qualified or generic).
     * @return The formatted type name string, or null if input is null.
     */
    public String format(String typeName) {
        if (typeName == null || this == FQN) {
            return typeName;
        } // if
        return simplify(typeName);
    } // format

    /**
     * Simplifies a fully qualified or parameterized type string by stripping dotted identifier prefixes.
     * This is a textual presentation operation, not Java type parsing or name resolution.
     * Dotted enclosing-type prefixes are removed as well as package prefixes; no class is loaded.
     *
     * @param typeName The type string to simplify, or null.
     * @return The simplified type string, or null if the input is null.
     */
    public static String simplify(String typeName) {
        if (typeName == null) {
            return null;
        } // if
        return PACKAGE_PREFIX_PATTERN.matcher(typeName).replaceAll("");
    } // simplify

    /**
     * Returns the lowercase identifier used for this presentation style.
     *
     * @return {@code fqn} or {@code simple}.
     */
    @Override
    public String toString() {
        return this.value;
    } // toString

} // TypeStyle
