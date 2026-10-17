package cs1302.tracer.internal.classpath;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/** Filesystem policies for binary dependency capture and disposal. */
final class ClassPathFiles {

    /** Prevents instantiation. */
    private ClassPathFiles() {} // ClassPathFiles

    /**
     * Captures directory contents, resolving an explicitly selected root but applying link policy below it.
     * The operation's own capture tree is skipped if it lies inside the selected directory.
     *
     * @param original Original directory.
     * @param target Destination directory, which must not exist.
     * @param followLinks Whether discovered links may be followed.
     * @throws IOException If a resource cannot be captured or violates the link policy.
     */
    static void copyDirectory(Path original, Path target, boolean followLinks) throws IOException {
        Path root = original.toRealPath();
        Path capture = target.getParent();
        Files.walkFileTree(root, options(followLinks), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            /** {@inheritDoc} */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSameFile(directory, capture)) {
                    return FileVisitResult.SKIP_SUBTREE;
                } // if
                Files.createDirectory(target.resolve(root.relativize(directory)));
                return FileVisitResult.CONTINUE;
            } // preVisitDirectory

            /** {@inheritDoc} */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Cannot copy class-path symbolic link: " + file
                            + "; enable link following for readable targets");
                } // if
                if (!attributes.isRegularFile()) {
                    throw new IOException("Class-path resource is not a regular file: " + file);
                } // if
                Files.copy(file, target.resolve(root.relativize(file)));
                return FileVisitResult.CONTINUE;
            } // visitFile
        });
    } // copyDirectory

    /**
     * Deletes only the owned tree; links are never traversed during cleanup.
     *
     * @param directory Owned directory.
     * @throws IOException If traversal or deletion fails.
     */
    static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            } // for
        } // try
    } // deleteDirectory

    /**
     * Selects the JDK traversal behavior.
     *
     * @param followLinks Whether encountered links may be followed.
     * @return File tree options.
     */
    private static Set<FileVisitOption> options(boolean followLinks) {
        return followLinks ? Set.of(FileVisitOption.FOLLOW_LINKS) : Set.of();
    } // options

} // ClassPathFiles
