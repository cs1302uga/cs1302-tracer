package cs1302.tracer;

import java.util.ArrayList;
import java.util.List;

/** Linear recognition of comment delimiters in multi-file source streams. */
public final class SourceDelimiters {

    /** Prevents instantiation. */
    private SourceDelimiters() {} // SourceDelimiters

    /**
     * A delimiter with offsets excluding its line terminator.
     * @param start Start offset.
     * @param end Exclusive end offset.
     * @param path Trimmed source path.
     */
    public record Delimiter(int start, int end, String path) {} // Delimiter

    /**
     * Finds file delimiters with work bounded by the input length.
     * @param source Source stream.
     * @return Delimiters in source order.
     */
    public static List<Delimiter> scan(CharSequence source) {
        List<Delimiter> result = new ArrayList<>();
        int start = 0;
        for (int end = 0; end <= source.length(); end++) {
            if (end == source.length() || isLineEnd(source.charAt(end))) {
                String path = path(source, start, end);
                if (path != null) {
                    result.add(new Delimiter(start, end, path));
                } // if
                start = end + 1;
            } // if
        } // for
        return result;
    } // scan

    /**
     * Recognizes the terminators used by Java's multiline regular expressions.
     * @param c Character.
     * @return Whether the character ends a line.
     */
    private static boolean isLineEnd(char c) {
        return switch (c) {
            case '\n', '\r', '\u0085', '\u2028', '\u2029' -> true;
            default -> false;
        }; // switch
    } // isLineEnd

    /**
     * Matches one complete delimiter line from its two ends.
     * @param source Source stream.
     * @param start Line start.
     * @param end Line end.
     * @return Trimmed path, or null when the line is not a delimiter.
     */
    private static String path(CharSequence source, int start, int end) {
        if (end - start < 2 || source.charAt(start) != '/' || source.charAt(start + 1) != '/') {
            return null;
        } // if
        int first = skipSpace(source, start + 2, end);
        int name = first;
        while (name < end && isMarker(source.charAt(name))) {
            name++;
        } // while
        if (name - first < 3) {
            return null;
        } // if
        name = skipSpace(source, name, end);
        int tail = skipTrailingSpace(source, name, end);
        int markersEnd = tail;
        while (tail > name && isMarker(source.charAt(tail - 1))) {
            tail--;
        } // while
        if (markersEnd - tail < 3) {
            return null;
        } // if
        tail = skipTrailingSpace(source, name, tail);
        String candidate = source.subSequence(name, tail).toString();
        return candidate.endsWith(".java") ? candidate.trim() : null;
    } // path

    /**
     * Skips trailing horizontal spaces.
     * @param source Source stream.
     * @param start Inclusive limit.
     * @param end Initial exclusive end.
     * @return End offset before trailing spaces.
     */
    private static int skipTrailingSpace(CharSequence source, int start, int end) {
        int tail = end;
        while (tail > start && isSpace(source.charAt(tail - 1))) {
            tail--;
        } // while
        return tail;
    } // skipTrailingSpace

    /**
     * Skips horizontal spaces.
     * @param source Source stream.
     * @param start Initial offset.
     * @param end Exclusive limit.
     * @return First nonspace offset.
     */
    private static int skipSpace(CharSequence source, int start, int end) {
        int next = start;
        while (next < end && isSpace(source.charAt(next))) {
            next++;
        } // while
        return next;
    } // skipSpace

    /**
     * Tests horizontal delimiter whitespace.
     * @param c Character.
     * @return Whether the character is space or tab.
     */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    } // isSpace

    /**
     * Tests delimiter marker characters.
     * @param c Character.
     * @return Whether the character is a marker.
     */
    private static boolean isMarker(char c) {
        return c == '-' || c == '=';
    } // isMarker
} // SourceDelimiters
