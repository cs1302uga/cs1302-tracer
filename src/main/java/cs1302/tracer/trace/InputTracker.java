package cs1302.tracer.trace;

/**
 * Tracks the progressive, monotonic consumption of standard input during guest program execution.
 */
public class InputTracker {

    private final String stdin;
    private int cursor;
    private int pendingBytes;

    /**
     * Constructs a new {@code InputTracker} with the specified standard input string.
     *
     * @param stdin The standard input string provided to the guest.
     */
    public InputTracker(String stdin) {
        this.stdin = stdin == null ? "" : stdin;
        this.cursor = 0;
    } // InputTracker

    /**
     * Returns the complete standard input string.
     *
     * @return The standard input string.
     */
    public String stdin() {
        return stdin;
    } // stdin

    /**
     * Returns the cumulative string of standard input consumed up to this point.
     *
     * @return Substring of consumed standard input.
     */
    public synchronized String consumed() {
        return stdin.substring(0, cursor);
    } // consumed

    /**
     * Returns the current character offset within the standard input.
     *
     * @return Current 0-based character index.
     */
    public synchronized int offset() {
        return cursor;
    } // offset

    /**
     * Returns the unconsumed portion of standard input.
     *
     * @return Unconsumed standard input substring.
     */
    public synchronized String unconsumed() {
        return stdin.substring(cursor);
    } // unconsumed

    /**
     * Returns true if all standard input has been consumed.
     *
     * @return True if cursor reached or exceeded standard input length.
     */
    public synchronized boolean isExhausted() {
        return cursor >= stdin.length();
    } // isExhausted

    /**
     * Records consumption of a token (e.g., from {@code Scanner.next*}), advancing past any
     * leading whitespace/delimiters and through the token itself.
     *
     * @param token The token consumed.
     */
    public synchronized void consumeToken(String token) {
        if (token == null || token.isEmpty() || cursor >= stdin.length()) {
            return;
        } // if
        int idx = stdin.indexOf(token, cursor);
        if (idx >= 0) {
            cursor = idx + token.length();
        } // if
    } // consumeToken

    /**
     * Records consumption of a full line (e.g., from {@code Scanner.nextLine},
     * {@code BufferedReader.readLine}, or {@code IO.readln}), advancing through the line
     * and consuming any immediate line terminator (CRLF, LF, or CR).
     *
     * @param line The line content consumed (excluding newline delimiter).
     */
    public synchronized void consumeLine(String line) {
        if (line == null || cursor >= stdin.length()) {
            return;
        } // if
        int idx = stdin.indexOf(line, cursor);
        if (idx >= 0) {
            cursor = idx + line.length();
            if (cursor < stdin.length()) {
                if (stdin.charAt(cursor) == '\r') {
                    cursor++;
                    if (cursor < stdin.length() && stdin.charAt(cursor) == '\n') {
                        cursor++;
                    } // if
                } else {
                    if (stdin.charAt(cursor) == '\n') {
                        cursor++;
                    } // if
                } // if
            } // if
        } // if
    } // consumeLine

    /**
     * Records raw UTF-8 byte consumption, advancing only through complete code points.
     * Character offsets remain UTF-16 indices into the supplied input string.
     *
     * @param count Number of bytes consumed by {@code InputStream.read}.
     */
    public synchronized void consumeBytes(int count) {
        if (count <= 0 || cursor >= stdin.length()) {
            return;
        } // if
        pendingBytes += count;
        while (cursor < stdin.length()) {
            int end = cursor + Character.charCount(stdin.codePointAt(cursor));
            int bytes = stdin.substring(cursor, end)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (pendingBytes < bytes) {
                break;
            } // if
            pendingBytes -= bytes;
            cursor = end;
        } // while
    } // consumeBytes

} // InputTracker
