package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSelectionTest {

    @TempDir
    Path temporary;

    @Test
    void utilityConstructorIsPrivate() throws Exception {
        var constructor = FileSelection.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void sortsEncounteredPathsAndIncludesHiddenRegularFilesWithoutExtensionFiltering() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("sources"));
        write(root, "demo/z-last.java");
        write(root, "demo/alpha/A.java");
        write(root, "demo/.hidden.java");
        write(root, "demo/.hidden/Nested.java");
        write(root, "demo/Z.java");
        write(root, "demo/text.txt");
        Files.createDirectory(root.resolve("demo/Directory.java"));
        write(root, "other/Excluded.java");

        List<FileSelection.Match> matches = FileSelection.sources(root, "demo/**", false);
        assertThat(matches).extracting(FileSelection.Match::sourcePath).containsExactly(
                "demo/.hidden.java", "demo/.hidden/Nested.java", "demo/Z.java", "demo/alpha/A.java",
                "demo/text.txt", "demo/z-last.java");
        for (FileSelection.Match match : matches) {
            assertThat(match.file()).isEqualTo(root.toRealPath().resolve(match.sourcePath()));
        }
        assertThatThrownBy(() -> matches.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(FileSelection.sources(root, "demo/**/*.java", false))
                .extracting(FileSelection.Match::sourcePath).containsExactly(
                        "demo/.hidden.java", "demo/.hidden/Nested.java", "demo/Z.java", "demo/alpha/A.java",
                        "demo/z-last.java");
    }

    @Test
    void rejectsEmptySelectionAndReportsRootAndPattern() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("empty"));
        Files.createDirectory(root.resolve("Directory.java"));
        assertThatThrownBy(() -> FileSelection.sources(root, "**/*.java", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No regular files match glob '**/*.java' under root '" + root + "'");
        assertThatThrownBy(() -> FileSelection.dependencies(root, "**/*.java", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No class-path files match glob '**/*.java' under root '" + root + "'");
    }

    @Test
    void validatesPatternBeforeAccessingRootAndRejectsMissingNonDirectoryAndNullRoots() throws Exception {
        Path missing = temporary.resolve("missing");
        assertThatThrownBy(() -> FileSelection.sources(missing, "[", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileSelection.sources(missing, "**", false))
                .isInstanceOf(IOException.class);
        Path file = Files.writeString(temporary.resolve("file.java"), "class File {}");
        assertThatThrownBy(() -> FileSelection.sources(file, "**", false))
                .isInstanceOf(NotDirectoryException.class).hasMessageContaining(file.toString());
        assertThatThrownBy(() -> FileSelection.sources(null, "**", false))
                .isInstanceOf(NullPointerException.class).hasMessage("root");
        assertThatThrownBy(() -> FileSelection.sources(temporary, null, false))
                .isInstanceOf(NullPointerException.class).hasMessage("glob");
        assertThatThrownBy(() -> FileSelection.sources(null, "[", false))
                .isInstanceOf(NullPointerException.class).hasMessage("root");
        assertThatThrownBy(() -> FileSelection.sources(null, null, false))
                .isInstanceOf(NullPointerException.class).hasMessage("root");
    }

    @Test
    void dependencySelectionCompilesItsPatternBeforeCheckingTheRoot() throws Exception {
        assertThatThrownBy(() -> FileSelection.dependencies(null, "[", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileSelection.dependencies(null, null, false))
                .isInstanceOf(NullPointerException.class).hasMessage("glob");
        assertThatThrownBy(() -> FileSelection.dependencies(null, "**", false))
                .isInstanceOf(NullPointerException.class);
        Path missing = temporary.resolve("missing");
        assertThatThrownBy(() -> FileSelection.dependencies(missing, "[", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileSelection.dependencies(missing, "**", false))
                .isInstanceOf(IOException.class);
        Path file = write(temporary, "File.jar");
        assertThatThrownBy(() -> FileSelection.dependencies(file, "**", false))
                .isInstanceOf(NotDirectoryException.class).hasMessageContaining(file.toString());
    }

    @Test
    void followsAnExplicitRootSymlinkInBothModes() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        write(real, "demo/Main.java");
        Path root = Files.createSymbolicLink(temporary.resolve("root"), real);
        for (boolean followLinks : new boolean[] { false, true }) {
            assertThat(FileSelection.sources(root, "**/*.java", followLinks)).containsExactly(
                    new FileSelection.Match("demo/Main.java", real.toRealPath().resolve("demo/Main.java")));
            assertThat(FileSelection.dependencies(root, "**/*.java", followLinks))
                    .containsExactly(root.resolve("demo/Main.java"));
        }
    }

    @Test
    void dependencyPathsRetainTheRootAliasAndImmutableLexicalOrder() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        write(real, "nested/Z.jar");
        write(real, ".hidden.jar");
        write(real, "A.jar");
        Path root = Files.createSymbolicLink(temporary.resolve("root-alias"), real);
        List<Path> matches = FileSelection.dependencies(root, "**/*.jar", false);
        assertThat(matches).containsExactly(root.resolve(".hidden.jar"), root.resolve("A.jar"),
                root.resolve("nested/Z.jar"));
        assertThatThrownBy(() -> matches.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsABrokenExplicitRootInBothModes() throws Exception {
        Path broken = Files.createSymbolicLink(temporary.resolve("broken"), temporary.resolve("missing"));
        for (boolean followLinks : new boolean[] { false, true }) {
            assertThatThrownBy(() -> FileSelection.sources(broken, "**", followLinks))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void skipsAllDiscoveredLinksByDefaultIncludingBrokenAndCyclicLinks() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path externalFile = write(outside, "Target.java");
        write(root, "Keep.java");
        Files.createSymbolicLink(root.resolve("File.java"), externalFile);
        Files.createSymbolicLink(root.resolve("directory"), outside);
        Files.createSymbolicLink(root.resolve("Broken.java"), outside.resolve("absent"));
        Files.createSymbolicLink(root.resolve("cycle"), root);
        assertThat(FileSelection.sources(root, "**", false))
                .extracting(FileSelection.Match::sourcePath).containsExactly("Keep.java");
    }

    @Test
    void followsExternalFileAndDirectoryAliasesWithoutPhysicalDeduplication() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path externalFile = write(outside, "Target.java");
        Files.createSymbolicLink(root.resolve("A.java"), externalFile);
        Files.createSymbolicLink(root.resolve("B.java"), externalFile);
        Files.createSymbolicLink(root.resolve("first"), outside);
        Files.createSymbolicLink(root.resolve("second"), outside);
        List<FileSelection.Match> matches = FileSelection.sources(root, "**/*.java", true);
        assertThat(matches).extracting(FileSelection.Match::sourcePath).containsExactly(
                "A.java", "B.java", "first/Target.java", "second/Target.java");
        for (FileSelection.Match match : matches) {
            assertThat(Files.isSameFile(match.file(), externalFile)).isTrue();
            assertThat(match.file()).isEqualTo(root.toRealPath().resolve(match.sourcePath()));
        }
    }

    @Test
    void preservesSeparateHardLinks() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path original = write(root, "Original.java");
        Files.createLink(root.resolve("Alias.java"), original);
        assertThat(FileSelection.sources(root, "*.java", false))
                .extracting(FileSelection.Match::sourcePath).containsExactly("Alias.java", "Original.java");
    }

    @Test
    void rejectsBrokenLinksEvenWhenTheirAliasDoesNotMatch() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        write(root, "Keep.java");
        Path broken = Files.createSymbolicLink(root.resolve("unmatched.txt"), temporary.resolve("absent"));
        assertThatThrownBy(() -> FileSelection.sources(root, "*.java", true))
                .isInstanceOf(IOException.class).hasMessage("Cannot follow symbolic link: "
                        + root.toRealPath().resolve(broken.getFileName()));
        assertThatThrownBy(() -> FileSelection.dependencies(root, "*.java", true))
                .isInstanceOf(IOException.class).hasMessage("Cannot follow class-path symbolic link: "
                        + root.toRealPath().resolve(broken.getFileName()));
    }

    @Test
    void rejectsActiveAncestryCyclesEvenWhenTheirAliasDoesNotMatch() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        write(root, "Keep.java");
        Path directory = Files.createDirectory(root.resolve("unmatched"));
        Files.createSymbolicLink(directory.resolve("back"), root);
        assertThatThrownBy(() -> FileSelection.sources(root, "*.java", true))
                .isInstanceOf(FileSystemLoopException.class);
    }

    @Test
    void ignoresNonRegularFileTargetsWhenFollowingLinks() throws Exception {
        Path device = Path.of("/dev/null");
        assumeTrue(Files.exists(device) && !Files.isRegularFile(device));
        Path root = Files.createDirectory(temporary.resolve("root"));
        write(root, "Keep.java");
        Files.createSymbolicLink(root.resolve("Device.java"), device);
        assertThat(FileSelection.sources(root, "*.java", true))
                .extracting(FileSelection.Match::sourcePath).containsExactly("Keep.java");
    }

    @Test
    void traversesUnmatchedDirectoriesAndPropagatesAccessFailures() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(temporary.resolve("root"));
        write(root, "Keep.java");
        Path blocked = Files.createDirectory(root.resolve("unmatched"));
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);
        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeFalse(Files.isReadable(blocked), "Privileged users can bypass directory permissions");
            for (boolean followLinks : new boolean[] { false, true }) {
                assertThatThrownBy(() -> FileSelection.sources(root, "*.java", followLinks))
                        .isInstanceOf(IOException.class).hasMessageContaining("unmatched");
            }
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }
    }

    @Test
    void retainsLiteralBackslashesWithinHostFilenameComponents() throws Exception {
        assumeFalse(temporary.getFileSystem().getSeparator().equals("\\"));
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path literal = write(root, "part\\name.java");
        assertThat(FileSelection.sources(root, "part\\\\name.java", false))
                .extracting(FileSelection.Match::sourcePath).containsExactly("part\\name.java");
        Path alias = Files.createSymbolicLink(temporary.resolve("root-alias"), root);
        assertThat(FileSelection.dependencies(alias, "part\\\\name.java", false))
                .containsExactly(alias.resolve(literal.getFileName()));
    }

    private static Path write(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "captured content");
    }

}
