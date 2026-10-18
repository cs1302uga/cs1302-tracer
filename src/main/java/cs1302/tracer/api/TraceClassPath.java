package cs1302.tracer.api;

import cs1302.tracer.internal.SourceGlob;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Immutable selection of local binary dependencies for one tracing or discovery operation.
 * Entries retain their supplied order. Construction does not access the filesystem; each operation
 * resolves relative paths against its preparation-time working directory, expands selections, and
 * follows local JAR manifest dependencies. The first normalized absolute path wins; distinct paths
 * remain distinct even when they identify the same physical file.
 * Manifest directory URLs require a trailing slash; directories named without it and archives named
 * with it are ignored. Literal path entries instead use their actual filesystem type.
 *
 * <p>Directories supply compiled classes and resources, not additional source discovery. The job's
 * compiled output precedes these dependencies. Borrowed inputs must remain available and unchanged
 * until the operation finishes. Optional copying captures the available dependency closure before
 * compilation and uses those copies throughout the operation, without modifying JAR contents.
 * Copying does not provide an atomic snapshot of a concurrently changing filesystem.
 * Source collection through {@link SourceBundle.Builder} instead captures file text when it is added.
 *
 * <p>This configuration does not resolve Maven coordinates or download dependencies, and it does not
 * implicitly include the embedding application's class path. Supply the directories and JARs needed
 * by the submitted program explicitly. Missing explicit inputs, unreadable files, and invalid
 * archives fail during preparation, appearing in the outcome or discovery diagnostics returned
 * by {@link Tracer}. Required values and glob syntax are checked when entries are constructed.
 *
 * @see Tracer#trace(TraceRequest, TraceClassPath)
 * @see Tracer#discoverBreakpoints(String, TraceClassPath)
 * @since 4.1.0
 */
@API(status = API.Status.STABLE, since = "4.1.0")
public final class TraceClassPath {

    private static final TraceClassPath EMPTY = new TraceClassPath(List.of(), false, false);

    private final List<Entry> entries;
    private final boolean copy;
    private final boolean followLinks;

    /**
     * Detaches an ordered selection without filesystem access.
     *
     * @param entries Requested dependency selections.
     * @param copy Whether preparation copies dependencies.
     * @param followLinks Whether discovered links may be followed.
     */
    private TraceClassPath(List<Entry> entries, boolean copy, boolean followLinks) {
        this.entries = List.copyOf(entries);
        this.copy = copy;
        this.followLinks = followLinks;
    } // TraceClassPath

    /**
     * Returns an empty dependency configuration with copying and link following disabled.
     *
     * @return Empty immutable configuration.
     */
    public static TraceClassPath empty() {
        return EMPTY;
    } // empty

    /**
     * Creates an empty builder with copying and link following disabled.
     *
     * @return Independent mutable builder.
     */
    public static Builder builder() {
        return new Builder(EMPTY);
    } // builder

    /**
     * Creates an independent builder retaining this configuration's ordered selections and options.
     *
     * @return Independent mutable builder.
     */
    public Builder toBuilder() {
        return new Builder(this);
    } // toBuilder

    /**
     * Returns the requested selections, before expansion or dependency discovery.
     * Repeated selections remain in this list; preparation, rather than configuration construction,
     * applies normalized-path duplicate handling.
     *
     * @return Immutable selections in supplied order.
     */
    public List<Entry> entries() {
        return this.entries;
    } // entries

    /**
     * Reports whether each operation captures dependency files before compilation.
     *
     * @return Whether copying is requested.
     */
    public boolean copy() {
        return this.copy;
    } // copy

    /**
     * Reports whether links encountered during glob traversal or directory copying may be followed.
     * Explicitly supplied paths retain normal filesystem resolution in either mode.
     *
     * @return Whether discovered links may be followed.
     */
    public boolean followLinks() {
        return this.followLinks;
    } // followLinks

    /**
     * A requested dependency selection whose files are resolved during operation preparation.
     * Use {@link PathEntry} for one literal path or {@link GlobEntry} for an explicit pattern group.
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public sealed interface Entry permits PathEntry, GlobEntry {} // Entry

    /**
     * One literal directory or JAR path, never interpreted as a glob or a path list.
     * Relative paths are resolved when an operation prepares its dependencies, not when this value
     * is constructed. A symbolic link supplied directly as this path uses normal path resolution.
     *
     * @param path Dependency path; existence is checked during preparation.
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public record PathEntry(Path path) implements Entry {

        /**
         * Requires a non-null path without accessing it.
         *
         * @param path Literal dependency path.
         * @throws NullPointerException If path is null.
         */
        public PathEntry {
            Objects.requireNonNull(path, "path");
        } // PathEntry

    } // PathEntry

    /**
     * A portable, case-sensitive pattern selecting regular files below an explicit root.
     * Patterns use forward-slash separators, ordinary wildcards, character classes, non-nested brace
     * alternatives, and whole-component {@code **} for zero or more directory levels. Backslash escapes
     * metacharacters. Absolute patterns, dot components, and empty components are invalid.
     * Each selection is sorted by its encountered root-relative paths and inserted at its original
     * position. A selection with no matches fails during preparation.
     * The complete pattern grammar is shared with
     * {@link SourceBundle.Builder#addFiles(Path, String, boolean)}; this selection reads no files
     * until a tracing or discovery operation uses it.
     *
     * @param root Search directory, relative to the preparation-time working directory when relative.
     * @param glob Portable root-relative pattern.
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public record GlobEntry(Path root, String glob) implements Entry {

        /**
         * Validates required values and pattern syntax without accessing the filesystem.
         *
         * @param root Search directory.
         * @param glob Portable root-relative pattern.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the pattern is malformed.
         */
        public GlobEntry {
            Objects.requireNonNull(root, "root");
            SourceGlob.compile(glob);
        } // GlobEntry

    } // GlobEntry

    /**
     * Mutable, reusable dependency selection; not thread-safe.
     * Building snapshots the selections and options without reading or copying dependency files.
     * Each build is independent of subsequent additions and option changes to the builder.
     */
    @API(status = API.Status.STABLE, since = "4.1.0")
    public static final class Builder {

        private final List<Entry> entries;
        private boolean copy;
        private boolean followLinks;

        /**
         * Copies requested selections and options.
         *
         * @param original Configuration to copy.
         */
        private Builder(TraceClassPath original) {
            this.entries = new ArrayList<>(original.entries());
            this.copy = original.copy();
            this.followLinks = original.followLinks();
        } // Builder

        /**
         * Appends one literal directory or JAR path.
         * The path need not exist yet; existence and readability are checked during preparation.
         * Use {@link #addGlob(Path, String)} to request pattern matching explicitly.
         *
         * @param path Literal dependency path.
         * @return This builder.
         * @throws NullPointerException If path is null.
         */
        public Builder addPath(Path path) {
            this.entries.add(new PathEntry(path));
            return this;
        } // addPath

        /**
         * Appends a portable pattern whose matches are selected during each operation.
         *
         * @param root Search directory.
         * @param glob Portable pattern; see {@link GlobEntry}.
         * @return This builder.
         * @throws NullPointerException If either argument is null.
         * @throws IllegalArgumentException If the pattern is malformed.
         */
        public Builder addGlob(Path root, String glob) {
            this.entries.add(new GlobEntry(root, glob));
            return this;
        } // addGlob

        /**
         * Selects copying of dependencies once per operation, including local manifest dependencies.
         * Owned copies are released when that operation finishes. This option leaves the caller's
         * original files intact and defaults to false for a new builder.
         *
         * @param copy True to use owned copies; false to borrow caller-owned inputs.
         * @return This builder.
         */
        public Builder copy(boolean copy) {
            this.copy = copy;
            return this;
        } // copy

        /**
         * Selects handling of encountered symbolic links. With false, glob traversal skips links and
         * copying directory contents rejects links. With true, both follow targets, including targets
         * outside the selected root; encountered broken links and directory cycles fail preparation.
         * The default is false. This option does not change resolution of an explicitly supplied
         * path or root that is itself a symbolic link.
         *
         * @param followLinks Whether encountered links may be followed.
         * @return This builder.
         */
        public Builder followLinks(boolean followLinks) {
            this.followLinks = followLinks;
            return this;
        } // followLinks

        /**
         * Builds an independent immutable configuration; an empty dependency selection is permitted.
         *
         * @return Immutable configuration.
         */
        public TraceClassPath build() {
            return new TraceClassPath(this.entries, this.copy, this.followLinks);
        } // build

    } // Builder

} // TraceClassPath
