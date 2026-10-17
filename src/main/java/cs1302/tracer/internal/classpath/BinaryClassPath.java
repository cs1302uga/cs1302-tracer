package cs1302.tracer.internal.classpath;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apiguardian.api.API;

/** Opens binary dependency directories without allowing consumers to expand JAR manifests again. */
@API(status = API.Status.INTERNAL, since = "4.1.0")
final class BinaryClassPath implements AutoCloseable {

    private final List<Path> roots = new ArrayList<>();
    private final List<FileSystem> archives = new ArrayList<>();

    /** Creates an empty owned view. */
    private BinaryClassPath() {} // BinaryClassPath

    /**
     * Opens runtime-version views of the already resolved dependencies.
     * @param entries Ordered dependency paths.
     * @return Owned views, closed before dependency copies are removed.
     * @throws IOException If an archive cannot be opened.
     */
    public static BinaryClassPath open(List<Path> entries) throws IOException {
        return open(entries, BinaryClassPath::openArchive);
    } // open

    /**
     * Opens one archive using the host runtime's multi-release selection.
     * @param entry Prepared archive path.
     * @return Owned archive reader.
     * @throws IOException If the archive cannot be opened.
     */
    static FileSystem openArchive(Path entry) throws IOException {
        return FileSystems.newFileSystem(entry, Map.of("releaseVersion", "runtime"));
    } // openArchive

    /**
     * Opens views through an archive I/O boundary, retaining cleanup failures on the original exception.
     *
     * @param entries Ordered dependency paths.
     * @param opener Archive filesystem factory.
     * @return Owned views.
     * @throws IOException If an archive cannot be opened.
     */
    static BinaryClassPath open(List<Path> entries, ArchiveOpener opener) throws IOException {
        BinaryClassPath result = new BinaryClassPath();
        try {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    result.roots.add(entry);
                } else {
                    FileSystem archive = opener.open(entry);
                    result.archives.add(archive);
                    result.roots.add(archive.getPath("/"));
                } // if
            } // for
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                result.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            } // try
            throw failure;
        } // try
    } // open

    /** Archive reader creation boundary; each returned filesystem transfers ownership to this view. */
    @FunctionalInterface
    interface ArchiveOpener {

        /**
         * Opens one version-selected archive filesystem.
         *
         * @param archive Dependency archive.
         * @return Owned archive reader.
         * @throws IOException If opening fails.
         */
        FileSystem open(Path archive) throws IOException;

    } // ArchiveOpener

    /**
     * Returns directories representing effective binary entries.
     * @return Immutable ordered directory roots.
     */
    public List<Path> roots() {
        return List.copyOf(this.roots);
    } // roots

    /** Releases archive views; caller-owned directories are unchanged. */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (FileSystem archive : this.archives) {
            try {
                archive.close();
            } catch (IOException problem) {
                if (failure == null) {
                    failure = problem;
                } else {
                    failure.addSuppressed(problem);
                } // if
            } // try
        } // for
        this.archives.clear();
        if (failure != null) {
            throw failure;
        } // if
    } // close

} // BinaryClassPath
