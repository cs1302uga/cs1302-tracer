package cs1302.tracer.internal.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.CompilationHelper;
import cs1302.tracer.internal.FaultPaths;
import cs1302.tracer.internal.SourcePreparation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryClassPathBoundaryTest {

    @TempDir
    Path temporary;

    @Test
    void closeAttemptsEveryReaderAndPreservesAllFailures() throws Exception {
        Path firstPath = jar("first.jar");
        Path secondPath = jar("second.jar");
        Path lastPath = jar("last.jar");
        IOException firstFailure = new IOException("first close failed");
        IOException secondFailure = new IOException("second close failed");
        var first = new CloseFailureFileSystem(openArchive(firstPath), firstFailure);
        var second = new CloseFailureFileSystem(openArchive(secondPath), secondFailure);
        FileSystem last = openArchive(lastPath);
        Map<Path, FileSystem> readers = Map.of(firstPath, first, secondPath, second, lastPath, last);
        BinaryClassPath prepared = BinaryClassPath.open(List.of(firstPath, secondPath, lastPath), readers::get);
        assertThat(prepared.roots()).hasSize(3);
        assertThatThrownBy(prepared::close).isSameAs(firstFailure);
        assertThat(firstFailure.getSuppressed()).containsExactly(secondFailure);
        assertThat(first.isOpen()).isFalse();
        assertThat(second.isOpen()).isFalse();
        assertThat(last.isOpen()).isFalse();
        prepared.close();
        assertThat(first.closeCalls).isEqualTo(1);
        assertThat(second.closeCalls).isEqualTo(1);
    }

    @Test
    void partialOpenKeepsThePrimaryErrorAndSuppressesReaderCleanupFailure() throws Exception {
        Path firstPath = jar("first.jar");
        Path secondPath = jar("second.jar");
        IOException opening = new IOException("second open failed");
        IOException closing = new IOException("first close failed");
        var first = new CloseFailureFileSystem(openArchive(firstPath), closing);
        assertThatThrownBy(() -> BinaryClassPath.open(List.of(firstPath, secondPath), path -> {
            if (path.equals(secondPath)) {
                throw opening;
            }
            return first;
        })).isSameAs(opening);
        assertThat(opening.getSuppressed()).containsExactly(closing);
        assertThat(first.isOpen()).isFalse();
        assertThat(first.closeCalls).isEqualTo(1);
    }

    @Test
    void preparationStillDeletesOutputAndCopiesWhenClosingAnArchiveReportsFailure() throws Exception {
        Path original = jar("dependency.jar");
        IOException closing = new IOException("archive close failed");
        var opened = new java.util.ArrayList<CloseFailureFileSystem>();
        PreparedClassPath dependencies = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(original).copy(true).build(), path -> {
                    var reader = new CloseFailureFileSystem(openArchive(path), closing);
                    opened.add(reader);
                    return reader;
                });
        Path copied = dependencies.entries().getFirst();
        var archive = opened.getFirst();
        Path output = Files.createDirectory(temporary.resolve("compiled"));
        var compilation = new CompilationHelper.CompilationResult(output, Set.of(), "Main", Optional.empty(), false);
        SourcePreparation preparation = new SourcePreparation(compilation, List.of(), dependencies);
        assertThatThrownBy(preparation::close).isInstanceOf(UncheckedIOException.class).hasCause(closing);
        assertThat(archive.isOpen()).isFalse();
        assertThat(copied).doesNotExist();
        assertThat(output).doesNotExist();
        assertThat(original).exists();
    }

    @Test
    void completePreparationClosesReadersAndRemovesCopiesWhenLaterArchiveOpeningFails() throws Exception {
        Path first = jar("first.jar");
        Path second = jar("second.jar");
        IOException opening = new IOException("second prepared archive failed");
        IOException closing = new IOException("first prepared reader failed");
        var captured = new java.util.ArrayList<Path>();
        var readers = new java.util.ArrayList<CloseFailureFileSystem>();
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(first).addPath(second).copy(true).build(), path -> {
                    captured.add(path);
                    if (captured.size() == 2) {
                        throw opening;
                    }
                    var reader = new CloseFailureFileSystem(openArchive(path), closing);
                    readers.add(reader);
                    return reader;
                })).isSameAs(opening);
        assertThat(opening.getSuppressed()).containsExactly(closing);
        assertThat(readers.getFirst().isOpen()).isFalse();
        assertThat(captured).hasSize(2).allSatisfy(path -> assertThat(path).doesNotExist());
        assertThat(captured.getFirst().getParent()).doesNotExist();
        assertThat(first).exists();
        assertThat(second).exists();
    }

    @Test
    void archiveCloseFailureRemainsPrimaryWhenCopyDeletionAlsoFailsAndCleanupCanBeRetried() throws Exception {
        Path original = jar("dependency.jar");
        IOException closing = new IOException("archive close failed");
        var readers = new java.util.ArrayList<CloseFailureFileSystem>();
        PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(original).copy(true).build(), path -> {
                    var reader = new CloseFailureFileSystem(openArchive(path), closing);
                    readers.add(reader);
                    return reader;
                });
        Path captured = prepared.entries().getFirst().getParent();
        // Substitute only the cleanup filesystem, keeping all dependency acquisition real.
        var directory = PreparedClassPath.class.getDeclaredField("directory");
        directory.setAccessible(true);
        directory.set(prepared, FaultPaths.refusingDeletion(captured));
        try {
            assertThatThrownBy(prepared::close).isSameAs(closing);
            assertThat(closing.getSuppressed()).singleElement()
                    .isInstanceOf(java.nio.file.AccessDeniedException.class);
            assertThat(readers.getFirst().isOpen()).isFalse();
            assertThat(captured).exists();
        } finally {
            directory.set(prepared, captured);
            prepared.close();
        }
        assertThat(readers.getFirst().closeCalls).isEqualTo(1);
        assertThat(captured).doesNotExist();
        assertThat(original).exists();
    }

    private Path jar(String name) throws IOException {
        Path archive = temporary.resolve(name);
        try (var stream = new JarOutputStream(Files.newOutputStream(archive))) {
            stream.putNextEntry(new JarEntry("data.txt"));
            stream.write(1);
            stream.closeEntry();
        }
        return archive;
    }

    private FileSystem openArchive(Path archive) throws IOException {
        return FileSystems.newFileSystem(archive, Map.of("releaseVersion", "runtime"));
    }

    /** Real archive readers with a deterministic close-boundary failure, independent of file permissions. */
    private static final class CloseFailureFileSystem extends FileSystem {

        private final FileSystem delegate;
        private final IOException failure;
        private int closeCalls;

        CloseFailureFileSystem(FileSystem delegate, IOException failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public FileSystemProvider provider() {
            return delegate.provider();
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            delegate.close();
            throw failure;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return delegate.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            return delegate.getRootDirectories();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return delegate.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return delegate.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return delegate.getPath(first, more);
        }

        @Override
        public PathMatcher getPathMatcher(String pattern) {
            return delegate.getPathMatcher(pattern);
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return delegate.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() throws IOException {
            return delegate.newWatchService();
        }

    }

}
