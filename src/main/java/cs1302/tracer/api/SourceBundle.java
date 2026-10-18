package cs1302.tracer.api;

import cs1302.tracer.internal.SourceDelimiters;
import cs1302.tracer.internal.FileSelection;
import cs1302.tracer.internal.SourceNames;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Immutable, ordered source files packaged in the tracer's delimited source format.
 * Every file, including a single-file bundle, has a canonical {@code // --- path.java ---}
 * header followed by LF. Original text, byte-order marks, and line endings are preserved.
 * Before a subsequent header, one LF is added only when the preceding text does not already
 * end with a delimiter-recognized line terminator. Empty files need no additional separator.
 * The final file's text is not given an extra terminator.
 *
 * <p>Construction validates packaging, not compilation or entry-point availability. Explicitly named
 * files may contain empty or invalid Java text. Inferred names require parseable source with exactly
 * one public top-level type, or otherwise exactly one top-level type. Use {@link #source()} with
 * {@link TraceRequest#builder(String)} or an existing source-text constructor.
 * File additions capture text immediately; later filesystem changes do not alter a built bundle.
 * Binary dependencies are selected separately through {@link TraceClassPath}.
 *
 * <p>Logical paths name the packaged inputs. Executable locations returned in
 * {@link BreakpointDiscovery} instead use package-relative debug source paths, which can differ
 * from logical paths such as {@code src/demo/Main.java}.
 *
 * @see Tracer#discoverBreakpoints(String)
 * @see Builder
 * @since 4.1.0
 */
@API(status = API.Status.STABLE, since = "4.1.0")
public final class SourceBundle {

    private final Map<String, String> sources;
    private final String source;

    /**
     * Captures validated originals and renders their ordered source stream.
     *
     * @param sources Validated source paths and original text in insertion order.
     */
    private SourceBundle(Map<String, String> sources) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : this.sources.entrySet()) {
            if (!text.isEmpty() && !SourceDelimiters.isLineEnd(text.charAt(text.length() - 1))) {
                text.append('\n');
            } // if
            text.append("// --- ").append(entry.getKey()).append(" ---\n").append(entry.getValue());
        } // for
        this.source = text.toString();
    } // SourceBundle

    /**
     * Creates an empty builder; add at least one file before building.
     *
     * @return A new mutable builder.
     */
    public static Builder builder() {
        return new Builder(Map.of());
    } // builder

    /**
     * Copies the captured original paths and text into an independent builder without rereading files.
     *
     * @return A mutable builder initialized with this bundle's files in their original order.
     */
    public Builder toBuilder() {
        return new Builder(this.sources);
    } // toBuilder

    /**
     * Returns the delimited source text for tracing, discovery, or other source-text consumers.
     * Headers are part of this transport string; each file's original text follows its header.
     * Calling this method performs no filesystem access or Java parsing.
     *
     * @return The immutable delimited source string.
     */
    public String source() {
        return this.source;
    } // source

    /**
     * Opens a fresh stream containing the UTF-8 encoding of {@link #source()}.
     * Each stream has independent bytes and cursor state, including when transferred to a custom output stream.
     *
     * @return A new caller-owned input stream positioned at its beginning.
     */
    public InputStream openStream() {
        return new ByteArrayInputStream(this.source.getBytes(StandardCharsets.UTF_8));
    } // openStream

    /**
     * Writes the UTF-8 encoding of {@link #source()} without flushing or closing the caller's destination.
     * The destination receives fresh bytes and cannot alter this bundle by retaining or modifying them.
     *
     * @param destination Caller-owned output stream.
     * @throws NullPointerException If the destination is null.
     * @throws IOException If the destination cannot accept the bytes.
     */
    public void writeUtf8(OutputStream destination) throws IOException {
        Objects.requireNonNull(destination, "destination").write(this.source.getBytes(StandardCharsets.UTF_8));
    } // writeUtf8

    /**
     * Mutable, reusable source packaging; not thread-safe.
     * Additions capture text immediately, and file additions read and strictly decode UTF-8 before returning.
     * Invalid additions leave existing entries unchanged. Building and copying never reread filesystem input.
     *
     * <p>Source paths are relative, end in {@code .java}, and use forward slashes in the bundle;
     * backslashes are normalized. Absolute paths, colons, empty or dot segments, control characters,
     * line terminators, and leading or trailing whitespace are rejected. Normalized duplicate paths are
     * rejected without replacement. Source and path strings must not contain unpaired UTF-16 surrogates.
     * Delimiter-shaped source lines are rejected with the source identifier and one-based line number.
     * CRLF counts as one line; CR, LF, NEL, U+2028, and U+2029 are recognized terminators.
     *
     * @since 4.1.0
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        private final Map<String, String> sources;

        /**
         * Copies already captured source entries in insertion order.
         *
         * @param sources Initial source entries.
         */
        private Builder(Map<String, String> sources) {
            this.sources = new LinkedHashMap<>(sources);
        } // Builder

        /**
         * Adds explicitly named source text without parsing or compiling its Java contents.
         * Paths identify bundle entries rather than filesystem inputs; use
         * {@link #addFile(String, Path)} to read a file instead.
         *
         * @param sourcePath Relative logical source path, normalized to forward slashes.
         * @param source Original source text, including an empty string when desired.
         * @return This builder.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the path is invalid or duplicated, text contains an unpaired
         *     surrogate, or a source line matches the delimiter grammar.
         */
        public Builder addSource(String sourcePath, String source) {
            return this.addCaptured(this.availablePath(sourcePath), source);
        } // addSource

        /**
         * Adds source text using its package and selected top-level type to infer a logical source path.
         * Exactly one public top-level type is selected; without a public type, there must be exactly one
         * top-level type. Parsing performs no compilation, symbol resolution, or main-method validation.
         *
         * @param source Original Java source text.
         * @return This builder.
         * @throws NullPointerException If source is null.
         * @throws IllegalArgumentException If source cannot be parsed or named unambiguously, or the
         *     resulting addition violates the path, encoding, duplicate, or delimiter rules.
         */
        public Builder addInferredSource(String source) {
            return this.addInferred(source, "inferred source");
        } // addInferredSource

        /**
         * Reads a UTF-8 file immediately and adds its captured text under an explicit logical source path.
         * The filesystem filename does not determine the logical path, and Java text is not parsed.
         * Later changes to the file do not affect this builder or its subsequently built bundles.
         *
         * @param sourcePath Relative logical source path, normalized to forward slashes.
         * @param file Filesystem input to read now.
         * @return This builder.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the path is invalid or duplicated, or a source line matches
         *     the delimiter grammar.
         * @throws IOException If reading fails or the file contains malformed UTF-8.
         */
        public Builder addFile(String sourcePath, Path file) throws IOException {
            String path = this.availablePath(sourcePath);
            return this.addCaptured(path, Files.readString(Objects.requireNonNull(file, "file"),
                    StandardCharsets.UTF_8));
        } // addFile

        /**
         * Reads a UTF-8 file immediately and infers its logical path from the captured Java text.
         * Selection follows {@link #addInferredSource(String)}, independently of the filesystem filename.
         *
         * @param file Filesystem input to read now.
         * @return This builder.
         * @throws NullPointerException If file is null.
         * @throws IllegalArgumentException If source cannot be parsed or named unambiguously, or the
         *     resulting addition violates the path, duplicate, or delimiter rules.
         * @throws IOException If reading fails or the file contains malformed UTF-8.
         */
        public Builder addInferredFile(Path file) throws IOException {
            Objects.requireNonNull(file, "file");
            return this.addInferred(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } // addInferredFile

        /**
         * Adds matching files under their root-relative logical paths without following discovered links.
         * Equivalent to {@link #addFiles(Path, String, boolean) addFiles(root, glob, false)}.
         *
         * @param root Directory from which relative paths are selected.
         * @param glob Portable root-relative glob, as described by the three-argument overload.
         * @return This builder.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the glob is invalid, no regular files match, or an addition
         *     violates the path, duplicate, or delimiter rules.
         * @throws IOException If traversal, reading, or strict UTF-8 decoding fails.
         */
        public Builder addFiles(Path root, String glob) throws IOException {
            return this.addFiles(root, glob, false);
        } // addFiles

        /**
         * Captures matching UTF-8 files under their root-relative logical paths, appending them in
         * case-sensitive, locale-independent lexical path order. Every matched path must satisfy the
         * existing relative {@code .java} path rules; invalid matches are errors, not filtered out.
         * Hidden regular files are eligible, and the glob supplies the only filename filter.
         *
         * <p>Globs use {@code /} separators on every platform and match Unicode code points case-sensitively.
         * They support
         * {@code *}, {@code ?}, character classes and ranges such as {@code [a-z]} and {@code [!0-9]},
         * non-nested brace alternatives, and backslash escapes for metacharacters. {@code **} must occupy
         * an entire path component and matches zero or more directory levels: <code>demo/**&#47;*.java</code>
         * includes both {@code demo/Main.java} and {@code demo/sub/Helper.java}. Brace groups require
         * at least two nonempty alternatives, which may contain separators and whole-component {@code **}.
         * Every alternative must form a relative pattern without empty, {@code .}, or {@code ..}
         * components or leading/trailing separators. Escapes are limited to {@code * ? [ ] { } , ! -}
         * and backslash itself. In classes a leading or trailing {@code -} is literal; ranges must be
         * ascending. Invalid syntax is rejected before filesystem traversal.
         *
         * <p>The explicitly selected root is resolved once and may itself be a symbolic link. With
         * {@code followLinks == false}, all discovered symbolic links are skipped, including broken
         * links. With {@code true}, file and directory links are followed, including targets outside the
         * root; encountered broken links, cycles, and traversal failures abort the call even when their
         * paths do not match. Selection and sorting use encountered alias paths, not resolved targets.
         * Distinct aliases are not deduplicated by physical identity.
         *
         * <p>The entire addition is atomic for this builder: traversal, validation, or reading failures
         * leave existing entries unchanged. Files are captured before return and never reread by
         * {@link #build()}; this does not promise a simultaneous snapshot of a changing filesystem.
         *
         * @param root Directory from which relative paths are selected.
         * @param glob Portable root-relative glob.
         * @param followLinks Whether to follow discovered symbolic links.
         * @return This builder.
         * @throws NullPointerException If root or glob is null.
         * @throws IllegalArgumentException If the glob is invalid, no regular files match, or an addition
         *     violates the path, duplicate, or delimiter rules.
         * @throws IOException If the root is not a readable directory, traversal fails, or reading or
         *     strict UTF-8 decoding fails.
         */
        public Builder addFiles(Path root, String glob, boolean followLinks) throws IOException {
            return this.addSelectedFiles(root, glob, followLinks, false);
        } // addFiles

        /**
         * Adds matching files with inferred logical paths without following discovered links.
         * Equivalent to {@link #addInferredFiles(Path, String, boolean) addInferredFiles(root, glob, false)}.
         *
         * @param root Directory from which relative paths are selected.
         * @param glob Portable root-relative glob.
         * @return This builder.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the glob is invalid, no regular files match, inference fails,
         *     or an addition violates the path, duplicate, or delimiter rules.
         * @throws IOException If traversal, reading, or strict UTF-8 decoding fails.
         */
        public Builder addInferredFiles(Path root, String glob) throws IOException {
            return this.addInferredFiles(root, glob, false);
        } // addInferredFiles

        /**
         * Captures matching UTF-8 files and infers each logical path from its captured Java source.
         * Selection, alias ordering, link handling, and whole-call atomicity follow
         * {@link #addFiles(Path, String, boolean)}. Naming follows {@link #addInferredSource(String)};
         * host filenames need not end in {@code .java}. Files remain ordered by their encountered
         * root-relative paths, independently of their inferred names. Duplicate inferred paths,
         * including separate aliases of the same physical file, abort the entire addition.
         *
         * @param root Directory from which relative paths are selected.
         * @param glob Portable root-relative glob.
         * @param followLinks Whether to follow discovered symbolic links.
         * @return This builder.
         * @throws NullPointerException If root or glob is null.
         * @throws IllegalArgumentException If the glob is invalid, no regular files match, inference fails,
         *     or an addition violates the path, duplicate, or delimiter rules.
         * @throws IOException If the root is not a readable directory, traversal fails, or reading or
         *     strict UTF-8 decoding fails.
         */
        public Builder addInferredFiles(Path root, String glob, boolean followLinks) throws IOException {
            return this.addSelectedFiles(root, glob, followLinks, true);
        } // addInferredFiles

        /**
         * Stages all selected files against a copy before publishing their captured entries.
         *
         * @param root Selection root.
         * @param glob Portable selection pattern.
         * @param followLinks Whether discovered links are followed.
         * @param infer Whether captured Java determines logical paths.
         * @return This builder after a successful atomic addition.
         * @throws IOException If selection or file capture fails.
         */
        private Builder addSelectedFiles(Path root, String glob, boolean followLinks, boolean infer)
                throws IOException {
            Builder staged = new Builder(this.sources);
            for (FileSelection.Match match : FileSelection.sources(root, glob, followLinks)) {
                if (infer) {
                    staged.addInferredFile(match.file());
                } else {
                    staged.addFile(match.sourcePath(), match.file());
                } // if
            } // for
            this.sources.putAll(staged.sources);
            return this;
        } // addSelectedFiles

        /**
         * Builds an independent immutable bundle from the captured files, preserving insertion order.
         * This operation performs no filesystem I/O, Java parsing, or compilation.
         * Later additions to this builder do not change the returned bundle.
         *
         * @return A new source bundle.
         * @throws IllegalStateException If no source files have been added.
         */
        public SourceBundle build() {
            if (this.sources.isEmpty()) {
                throw new IllegalStateException("A source bundle requires at least one file");
            } // if
            return new SourceBundle(this.sources);
        } // build

        /**
         * Validates and inserts captured text after path validation, without partial mutation on failure.
         *
         * @param path Validated, unused logical path.
         * @param source Captured source text.
         * @return This builder.
         */
        private Builder addCaptured(String path, String source) {
            Objects.requireNonNull(source, "source");
            validateEncoding(source, path);
            rejectDelimiters(source, path);
            this.sources.put(path, source);
            return this;
        } // addCaptured

        /**
         * Infers a path, retaining an input identifier for collision diagnostics if inference fails.
         *
         * @param source Captured source text.
         * @param identifier Description of the input before a logical path is known.
         * @return This builder after a successful addition.
         */
        private Builder addInferred(String source, String identifier) {
            Objects.requireNonNull(source, "source");
            validateEncoding(source, identifier);
            String path;
            try {
                path = SourceNames.inferPath(source);
            } catch (IllegalArgumentException failure) {
                rejectDelimiters(source, identifier);
                throw failure;
            } // try
            return this.addSource(path, source);
        } // addInferred

        /**
         * Validates a logical path and rejects an already captured normalized path.
         *
         * @param sourcePath Requested path.
         * @return The normalized, currently unused path.
         */
        private String availablePath(String sourcePath) {
            String path = normalizePath(sourcePath);
            if (this.sources.containsKey(path)) {
                throw new IllegalArgumentException("Duplicate source path: " + path);
            } // if
            return path;
        } // availablePath

    } // Builder

    /**
     * Normalizes separators and rejects paths that cannot be represented as relative delimiter paths.
     *
     * @param sourcePath Requested logical path.
     * @return The validated path using forward slashes.
     */
    private static String normalizePath(String sourcePath) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        validateEncoding(sourcePath, "source path");
        String path = sourcePath.replace('\\', '/');
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Invalid source path: " + sourcePath);
        } // if
        if (isPathSpace(path.charAt(0)) || isPathSpace(path.charAt(path.length() - 1))) {
            throw new IllegalArgumentException("Invalid source path: " + sourcePath);
        } // if
        if (path.indexOf(':') >= 0 || !path.endsWith(".java")) {
            throw new IllegalArgumentException("Invalid source path: " + sourcePath);
        } // if
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isISOControl(c) || SourceDelimiters.isLineEnd(c)) {
                throw new IllegalArgumentException("Invalid source path: " + sourcePath);
            } // if
        } // for
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid source path: " + sourcePath);
            } // if
        } // for
        return path;
    } // normalizePath

    /**
     * Recognizes whitespace prohibited at the edges of logical paths, including nonbreaking spaces.
     *
     * @param c Path-edge character.
     * @return Whether the character is whitespace or a Unicode space character.
     */
    private static boolean isPathSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    } // isPathSpace

    /**
     * Rejects UTF-16 text that cannot be encoded losslessly as UTF-8.
     *
     * @param text Text to validate.
     * @param identifier Source path or input description for diagnostics.
     */
    private static void validateEncoding(String text, String identifier) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            throw new IllegalArgumentException("Unpaired UTF-16 surrogate in " + identifier);
        } // if
    } // validateEncoding

    /**
     * Rejects source text that would be split into additional files by the stream reader.
     *
     * @param text Source text.
     * @param identifier Source path or input description for diagnostics.
     */
    private static void rejectDelimiters(String text, String identifier) {
        List<SourceDelimiters.Delimiter> delimiters = SourceDelimiters.scan(text);
        if (!delimiters.isEmpty()) {
            int line = lineNumber(text, delimiters.getFirst().start());
            throw new IllegalArgumentException("Source " + identifier + " contains a file delimiter at line " + line);
        } // if
    } // rejectDelimiters

    /**
     * Counts delimiter-recognized line endings before an offset, treating CRLF as one ending.
     *
     * @param text Source text.
     * @param offset Exclusive endpoint for counting.
     * @return One-based line number.
     */
    private static int lineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (SourceDelimiters.isLineEnd(c)) {
                line++;
                if (c == '\r' && i + 1 < offset && text.charAt(i + 1) == '\n') {
                    i++;
                } // if
            } // if
        } // for
        return line;
    } // lineNumber

} // SourceBundle
