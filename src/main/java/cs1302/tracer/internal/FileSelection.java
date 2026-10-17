package cs1302.tracer.internal;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.apiguardian.api.API;

/**
 * Selects regular files using portable globs, encountered root-relative paths, and an explicit link policy.
 * Source capture and dependency preparation retain their distinct path representations and read timing.
 * Selection performs no content reading, Java parsing, dependency expansion, or copying.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class FileSelection {

    /** Prevents instantiation. */
    private FileSelection() {} // FileSelection

    /**
     * One source candidate, retaining aliases encountered below the resolved traversal root.
     *
     * @param sourcePath Root-relative path with forward-slash component separators.
     * @param file Filesystem path to read beneath the resolved root.
     */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public record Match(String sourcePath, Path file) {} // Match

    /**
     * Selects source candidates with portable logical names and paths beneath the resolved root.
     * The root is checked for null before the pattern is compiled; compilation precedes filesystem access.
     *
     * <p>The explicit root is resolved even when it is a symbolic link. Discovered links are skipped unless
     * following is requested, which can leave the root. Traversal examines the whole tree and propagates
     * failures, including broken followed links and active ancestry cycles. Separate aliases remain separate
     * candidates. There is no extension filter, hidden-file filter, or simultaneous filesystem snapshot.
     *
     * @param root Directory whose encountered relative paths are matched.
     * @param glob Portable pattern compiled before accessing the filesystem.
     * @param followLinks Whether to follow symbolic links encountered below the root.
     * @return Immutable, nonempty source candidates in case-sensitive lexical relative-path order.
     * @throws NullPointerException If root or glob is null.
     * @throws IllegalArgumentException If the pattern is malformed or no regular files match.
     * @throws IOException If root is not an accessible directory, or traversal fails.
     */
    public static List<Match> sources(Path root, String glob, boolean followLinks) throws IOException {
        Objects.requireNonNull(root, "root");
        return select(root, glob, followLinks, Profile.SOURCE).stream()
                .map(selected -> new Match(selected.name(), selected.file())).toList();
    } // sources

    /**
     * Selects dependency candidates projected onto the caller's encountered root.
     * Traversal resolves the root, but returned paths retain its original alias and native path components.
     * The pattern is compiled before checking the root or accessing the filesystem. Traversal, link handling,
     * filtering, and ordering follow {@link #sources(Path, String, boolean)}.
     *
     * @param root Absolute normalized encountered directory, preserved in the returned paths.
     * @param glob Portable pattern compiled before accessing the filesystem.
     * @param followLinks Whether to follow symbolic links encountered below the root.
     * @return Immutable, nonempty encountered paths in case-sensitive lexical relative-path order.
     * @throws NullPointerException If root or glob is null.
     * @throws IllegalArgumentException If the pattern is malformed or no regular files match.
     * @throws IOException If root is not an accessible directory, or traversal fails.
     */
    public static List<Path> dependencies(Path root, String glob, boolean followLinks) throws IOException {
        return select(root, glob, followLinks, Profile.DEPENDENCY).stream()
                .map(selected -> root.resolve(selected.relative())).toList();
    } // dependencies

    /** Caller-specific diagnostics for the two selection operations. */
    private enum Profile {

        SOURCE("Cannot follow symbolic link: ", "No regular files match glob '"),
        DEPENDENCY("Cannot follow class-path symbolic link: ", "No class-path files match glob '");

        /** Followed-link failure prefix. */
        private final String linkFailure;

        /** Empty-selection failure prefix. */
        private final String noMatches;

        /**
         * Creates one caller profile without exposing traversal configuration to callers.
         *
         * @param linkFailure Followed-link failure prefix.
         * @param noMatches Empty-selection failure prefix.
         */
        Profile(String linkFailure, String noMatches) {
            this.linkFailure = linkFailure;
            this.noMatches = noMatches;
        } // Profile

    } // Profile

    /**
     * One traversal result with both portable ordering and native projection evidence.
     *
     * @param name Portable encountered relative name.
     * @param relative Native relative path, never reconstructed from the portable name.
     * @param file Encountered path below the resolved root.
     */
    private record Selected(String name, Path relative, Path file) {} // Selected

    /**
     * Applies the shared traversal while preserving each caller's diagnostics.
     *
     * @param root Encountered traversal root.
     * @param glob Portable pattern.
     * @param followLinks Whether discovered links may be followed.
     * @param profile Caller diagnostic profile.
     * @return Ordered, immutable traversal results.
     * @throws IOException If the root or traversal is inaccessible.
     */
    private static List<Selected> select(Path root, String glob, boolean followLinks, Profile profile)
            throws IOException {
        SourceGlob pattern = SourceGlob.compile(glob);
        Path directory = root.toRealPath();
        if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory()) {
            throw new NotDirectoryException(root.toString());
        } // if
        List<Selected> matches = new ArrayList<>();
        Set<FileVisitOption> options = followLinks ? Set.of(FileVisitOption.FOLLOW_LINKS) : Set.of();
        Files.walkFileTree(directory, options, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            /** {@inheritDoc} */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                // The JDK falls back to link attributes when following a link fails.
                if (followLinks && attributes.isSymbolicLink()) {
                    throw new IOException(profile.linkFailure + file);
                } // if
                if (attributes.isRegularFile()) {
                    Path relative = directory.relativize(file);
                    String name = relativePath(relative);
                    if (pattern.matches(name)) {
                        matches.add(new Selected(name, relative, file));
                    } // if
                } // if
                return FileVisitResult.CONTINUE;
            } // visitFile
        });
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(profile.noMatches + glob + "' under root '" + root + "'");
        } // if
        matches.sort(Comparator.comparing(Selected::name));
        return List.copyOf(matches);
    } // select

    /**
     * Joins path components without reinterpreting literal backslashes in host filenames.
     *
     * @param relative Root-relative native path.
     * @return Relative component names joined by forward slashes.
     */
    private static String relativePath(Path relative) {
        StringJoiner path = new StringJoiner("/");
        for (Path component : relative) {
            path.add(component.toString());
        } // for
        return path.toString();
    } // relativePath

} // FileSelection
