package cs1302.tracer.internal.classpath;

import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.FileSelection;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.apiguardian.api.API;

/**
 * One operation's ordered local dependency closure, archive views, and optional owned copies.
 * Manifest lookup uses original encountered locations; consumers receive only effective locations
 * and must not expand manifests again. Archive readers remain open until this owner closes.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class PreparedClassPath implements AutoCloseable {

    private final List<Path> entries;
    private final BinaryClassPath binaries;
    private Path directory;

    /**
     * Detaches prepared paths and accepts responsibility for any captured tree.
     *
     * @param entries Effective paths.
     * @param directory Owned capture directory, or null for borrowed inputs.
     * @param binaries Correlated archive views, or null during selection.
     */
    private PreparedClassPath(List<Path> entries, Path directory, BinaryClassPath binaries) {
        this.entries = List.copyOf(entries);
        this.directory = directory;
        this.binaries = binaries;
    } // PreparedClassPath

    /**
     * Selects dependencies, follows the local manifest closure, and optionally captures its contents.
     * No prepared view escapes on failure. Missing manifest references and invalid/non-file URLs are
     * ignored under local JAR semantics; missing explicit paths and present invalid archives fail.
     * Manifest directory URLs require a trailing slash; incompatible URL/file kinds are ignored.
     * Dependencies are deduplicated by normalized absolute path, retaining distinct physical aliases.
     *
     * @param configuration Immutable dependency selections and capture policy.
     * @return Owned operation-scoped dependency view.
     * @throws NullPointerException If configuration is null.
     * @throws IllegalArgumentException If a selection has no matches or uses a nonlocal filesystem.
     * @throws IOException If an explicit input or existing dependency cannot be read or copied.
     */
    public static PreparedClassPath prepare(TraceClassPath configuration) throws IOException {
        return prepare(configuration, BinaryClassPath::openArchive);
    } // prepare

    /**
     * Prepares the complete selection before acquiring archive readers through an internal I/O seam.
     * @param configuration Dependency selections and capture policy.
     * @param opener Archive reader factory.
     * @return Complete dependency owner.
     * @throws IOException If selection, copying, or archive opening fails.
     */
    static PreparedClassPath prepare(TraceClassPath configuration, BinaryClassPath.ArchiveOpener opener)
            throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Deque<Dependency> pending = selections(configuration, workingDirectory);
        Path capture = configuration.copy() && !pending.isEmpty()
                ? Files.createTempDirectory("tracer-class-path-") : null;
        try (PreparedClassPath owner = new PreparedClassPath(List.of(), capture, null)) {
            List<Path> entries = new ArrayList<>();
            Set<Path> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                Dependency dependency = pending.removeFirst();
                Path original = dependency.path();
                if (visited.contains(original)) {
                    continue;
                } // if
                BasicFileAttributes attributes = attributes(dependency);
                if (attributes == null) {
                    continue;
                } // if
                visited.add(original);
                Path effective = original;
                if (capture != null) {
                    effective = capture.resolve("entry-" + entries.size());
                    if (attributes.isDirectory()) {
                        ClassPathFiles.copyDirectory(original, effective, configuration.followLinks());
                    } else {
                        Files.copy(original, effective);
                    } // if
                } // if
                entries.add(effective);
                if (!attributes.isDirectory()) {
                    List<Dependency> references = manifestDependencies(original, effective);
                    for (int index = references.size() - 1; index >= 0; index--) {
                        pending.addFirst(references.get(index));
                    } // for
                } // if
            } // while
            PreparedClassPath result = new PreparedClassPath(entries, capture, BinaryClassPath.open(entries, opener));
            owner.directory = null;
            return result;
        } // try
    } // prepare

    /**
     * Returns the effective dependency search order, excluding the separately owned compilation output.
     *
     * @return Immutable dependency paths, valid until this view is closed.
     */
    public List<Path> entries() {
        return this.entries;
    } // entries

    /**
     * Returns the ordered host directory views of exactly the effective dependency entries.
     * @return Immutable roots valid until this owner closes.
     */
    public List<Path> binaryRoots() {
        return this.binaries.roots();
    } // binaryRoots

    /**
     * Closes archive readers before releasing captured files; borrowed inputs are never removed.
     * Cleanup attempts both resources and preserves secondary failures. Successful cleanup is idempotent.
     *
     * @throws IOException If an archive reader or captured file cannot be released.
     */
    @Override
    public void close() throws IOException {
        try {
            if (this.binaries != null) {
                this.binaries.close();
            } // if
        } catch (IOException | RuntimeException | Error failure) {
            try {
                this.deleteCopies();
            } catch (IOException | RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            } // try
            throw failure;
        } // try
        this.deleteCopies();
    } // close

    /**
     * Removes the owned capture tree without touching borrowed inputs.
     * @throws IOException If owned files cannot be removed.
     */
    private void deleteCopies() throws IOException {
        if (this.directory != null) {
            ClassPathFiles.deleteDirectory(this.directory);
            this.directory = null;
        } // if
    } // deleteCopies

    /**
     * Expands explicit selections while retaining supplied group positions.
     *
     * @param configuration Requested selections.
     * @param workingDirectory Preparation-time process working directory.
     * @return Pending explicit dependencies.
     * @throws IOException If pattern traversal fails.
     */
    private static Deque<Dependency> selections(TraceClassPath configuration, Path workingDirectory)
            throws IOException {
        Deque<Dependency> result = new ArrayDeque<>();
        for (TraceClassPath.Entry entry : configuration.entries()) {
            List<Path> paths;
            if (entry instanceof TraceClassPath.PathEntry literal) {
                paths = List.of(resolve(workingDirectory, literal.path()));
            } else {
                TraceClassPath.GlobEntry pattern = (TraceClassPath.GlobEntry) entry;
                paths = FileSelection.dependencies(resolve(workingDirectory, pattern.root()), pattern.glob(),
                        configuration.followLinks());
            } // if
            for (Path path : paths) {
                result.addLast(new Dependency(path, true, false));
            } // for
        } // for
        return result;
    } // selections

    /**
     * Resolves a host filesystem selection without collapsing physical aliases.
     *
     * @param workingDirectory Preparation-time working directory.
     * @param path Requested path.
     * @return Absolute normalized path.
     */
    private static Path resolve(Path workingDirectory, Path path) {
        if (path.getFileSystem() != FileSystems.getDefault()) {
            throw new IllegalArgumentException("Class-path entries require the host filesystem: " + path);
        } // if
        return workingDirectory.resolve(path).normalize();
    } // resolve

    /**
     * Validates one dependency while preserving Java's missing-manifest-target behavior.
     *
     * @param dependency Pending dependency with origin policy.
     * @return File attributes, or null for an absent or URL-kind-incompatible manifest dependency.
     * @throws IOException If an explicit dependency is absent or an entry is not a directory or file.
     */
    private static BasicFileAttributes attributes(Dependency dependency) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(dependency.path(), BasicFileAttributes.class);
            if (!dependency.explicit() && dependency.directoryUrl() != attributes.isDirectory()) {
                return null;
            } // if
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new IOException("Class-path entry is not a directory or regular JAR file: " + dependency.path());
            } // if
            return attributes;
        } catch (NoSuchFileException exception) {
            if (dependency.explicit()) {
                throw exception;
            } // if
            return null;
        } // try
    } // attributes

    /**
     * Reads captured manifest bytes while resolving their URLs against the original archive location.
     *
     * @param original Encountered archive location.
     * @param effective Archive bytes to read.
     * @return Local manifest references in declaration order.
     * @throws IOException If the existing archive cannot be opened or its manifest cannot be read.
     */
    @SuppressWarnings("deprecation") // URL context resolution preserves the JDK's file:relative manifest semantics.
    private static List<Dependency> manifestDependencies(Path original, Path effective) throws IOException {
        List<Dependency> result = new ArrayList<>();
        try (JarFile archive = new JarFile(effective.toFile())) {
            Manifest manifest = archive.getManifest();
            if (manifest == null) {
                return result;
            } // if
            String value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (value == null) {
                return result;
            } // if
            StringTokenizer references = new StringTokenizer(value);
            while (references.hasMoreTokens()) {
                try {
                    URL reference = new URL(original.toUri().toURL(), references.nextToken());
                    String host = reference.getHost();
                    if ("file".equalsIgnoreCase(reference.getProtocol())) {
                        // Local JDK file lookup decodes getFile(), including any literal query component.
                        String authority = host.isEmpty() || "localhost".equalsIgnoreCase(host)
                                ? "" : "//" + reference.getAuthority();
                        URI local = new URI("file:" + authority + reference.getFile().replace("?", "%3F"));
                        result.add(new Dependency(Path.of(local).toAbsolutePath().normalize(), false,
                                reference.getFile().endsWith("/")));
                    } // if
                } catch (MalformedURLException | URISyntaxException | IllegalArgumentException ignored) {
                    // The JAR specification ignores invalid Class-Path URLs.
                } // try
            } // while
        } catch (IOException exception) {
            throw new IOException("Cannot read class-path JAR '" + original + "': " + exception.getMessage(),
                    exception);
        } // try
        return result;
    } // manifestDependencies

    /**
     * One encountered dependency and its missing-input policy.
     *
     * @param path Absolute normalized encountered path.
     * @param explicit Whether absence is an error.
     * @param directoryUrl Whether a manifest URL requires a directory; unused for explicit paths.
     */
    private record Dependency(Path path, boolean explicit, boolean directoryUrl) {} // Dependency

} // PreparedClassPath
