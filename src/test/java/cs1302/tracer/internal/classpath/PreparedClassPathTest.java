package cs1302.tracer.internal.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cs1302.tracer.api.TraceClassPath;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedClassPathTest {

    @TempDir
    Path temporary;

    @Test
    void emptyViewsAreNoopsAndNullConfigurationIsRejected() throws Exception {
        for (boolean copy : new boolean[] { false, true }) {
            PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder().copy(copy).build());
            assertThat(prepared.entries()).isEmpty();
            prepared.close();
            prepared.close();
        }
        assertThatThrownBy(() -> PreparedClassPath.prepare(null))
                .isInstanceOf(NullPointerException.class).hasMessage("configuration");
        var constructor = ClassPathFiles.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void resolvesRelativePathsDeduplicatesNormalizedPathsAndPreservesAliases() throws Exception {
        Path jar = jar(temporary.resolve("library.zip"), null, true);
        Path alias = Files.createSymbolicLink(temporary.resolve("alias.jar"), jar);
        Path hardLink = Files.createLink(temporary.resolve("hard.jar"), jar);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        Path relative = Path.of("").toAbsolutePath().relativize(jar);
        TraceClassPath configuration = TraceClassPath.builder().addPath(relative)
                .addPath(temporary.resolve("not-a-directory/../library.zip"))
                .addPath(alias).addPath(hardLink).addPath(classes).build();
        try (PreparedClassPath prepared = PreparedClassPath.prepare(configuration)) {
            assertThat(prepared.entries()).containsExactly(jar, alias, hardLink, classes);
            assertThatThrownBy(() -> prepared.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(jar).exists();
        assertThat(alias).exists();
        assertThat(hardLink).exists();
        assertThat(classes).isDirectory();
    }

    @Test
    void manifestClosureUsesDepthFirstDeclarationOrderAndStopsCycles() throws Exception {
        Path root = jar(temporary.resolve("root.jar"), "b.jar c.jar missing.jar", true);
        Path b = jar(temporary.resolve("b.jar"), "d.jar root.jar", true);
        Path c = jar(temporary.resolve("c.jar"), "d.jar", true);
        Path d = jar(temporary.resolve("d.jar"), null, false);
        try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(root).addPath(c).addPath(b).build())) {
            assertThat(prepared.entries()).containsExactly(root, b, d, c);
        }
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(root)
                .addPath(temporary.resolve("missing.jar")).build())).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void manifestDirectoryIntentRequiresATrailingSlashAndArchivesMustNotHaveOne() throws Exception {
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        Path dependency = jar(temporary.resolve("dependency.jar"), null, false);
        Path invalidOnly = jar(temporary.resolve("invalid-only.jar"), "classes dependency.jar/", true);
        Path root = jar(temporary.resolve("root.jar"), "classes dependency.jar/ dependency.jar classes/", true);
        for (boolean copy : new boolean[] { false, true }) {
            try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                    .addPath(invalidOnly).copy(copy).build())) {
                assertThat(prepared.entries()).hasSize(1);
                assertThat(Files.readAllBytes(prepared.entries().getFirst())).isEqualTo(Files.readAllBytes(invalidOnly));
            }
            try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                    .addPath(root).copy(copy).build())) {
                assertThat(prepared.entries()).hasSize(3);
                assertThat(Files.readAllBytes(prepared.entries().get(1))).isEqualTo(Files.readAllBytes(dependency));
                assertThat(prepared.entries().getLast()).isDirectory();
            }
        }
        try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(classes).addPath(dependency).build())) {
            assertThat(prepared.entries()).containsExactly(classes, dependency);
        }
    }

    @Test
    void ignoresInvalidAndNonlocalManifestUrlsButAcceptsAbsoluteAndEncodedLocalPaths() throws Exception {
        Path absolute = jar(temporary.resolve("absolute.jar"), null, true);
        Path spaced = jar(temporary.resolve("space name.jar"), null, true);
        Path relative = jar(temporary.resolve("relative.jar"), null, true);
        Path localhost = jar(temporary.resolve("localhost.jar"), null, true);
        Path directory = Files.createDirectory(temporary.resolve("classes"));
        String references = "https://example.invalid/remote.jar http://[bad %broken "
                + "file:/bad?query=1 file://remote.invalid/ignored.jar " + absolute.toUri()
                + " space%20name.jar classes/ file:relative.jar file://localhost" + localhost.toUri().getRawPath();
        Path root = jar(temporary.resolve("root.jar"), references, true);
        try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder().addPath(root).build())) {
            assertThat(prepared.entries()).containsExactly(root, absolute, spaced, directory, relative, localhost);
        }
    }

    @Test
    void copiesWholeManifestClosureWithoutChangingArchiveBytesOrFollowingOriginalsAfterward() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("resources"));
        Files.createDirectory(resources.resolve("nested"));
        Files.writeString(resources.resolve("nested/data.txt"), "captured resource");
        Path absolute = jar(temporary.resolve("absolute.jar"), null, false);
        Path nested = jar(temporary.resolve("nested.jar"), absolute.toUri().toString(), true);
        Path root = jar(temporary.resolve("root.jar"), "nested.jar resources/", true);
        byte[] rootBytes = Files.readAllBytes(root);
        byte[] nestedBytes = Files.readAllBytes(nested);
        byte[] absoluteBytes = Files.readAllBytes(absolute);
        PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder().addPath(root).copy(true).build());
        List<Path> copies = prepared.entries();
        assertThat(copies).hasSize(4).doesNotContain(root, nested, absolute, resources);
        Path capture = copies.getFirst().getParent();
        assertThat(Files.readAllBytes(copies.get(0))).isEqualTo(rootBytes);
        assertThat(Files.readAllBytes(copies.get(1))).isEqualTo(nestedBytes);
        assertThat(Files.readAllBytes(copies.get(2))).isEqualTo(absoluteBytes);
        Files.delete(absolute);
        Files.delete(nested);
        Files.writeString(root, "changed original");
        Files.writeString(resources.resolve("nested/data.txt"), "changed resource");
        assertThat(Files.readAllBytes(copies.get(0))).isEqualTo(rootBytes);
        assertThat(Files.readAllBytes(copies.get(1))).isEqualTo(nestedBytes);
        assertThat(Files.readAllBytes(copies.get(2))).isEqualTo(absoluteBytes);
        assertThat(Files.readString(copies.get(3).resolve("nested/data.txt"))).isEqualTo("captured resource");
        prepared.close();
        prepared.close();
        assertThat(capture).doesNotExist();
        assertThat(root).exists();
        assertThat(resources).exists();
    }

    @Test
    void expandsSortedGlobGroupsInPlaceAndPreservesRootAliases() throws Exception {
        Path first = jar(temporary.resolve("first.jar"), null, false);
        Path last = jar(temporary.resolve("last.jar"), null, false);
        Path root = Files.createDirectory(temporary.resolve("libraries"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path z = jar(root.resolve("z.jar"), null, false);
        Path a = jar(root.resolve("A.jar"), null, false);
        Path hidden = jar(root.resolve(".hidden.jar"), null, false);
        Path deep = jar(nested.resolve("deep.jar"), null, false);
        Files.writeString(root.resolve("excluded.txt"), "ignored");
        Files.createDirectory(root.resolve("directory.jar"));
        Path alias = Files.createSymbolicLink(temporary.resolve("root-alias"), root);
        TraceClassPath configuration = TraceClassPath.builder().addPath(first)
                .addGlob(alias, "**/*.jar").addPath(z).addPath(last).build();
        try (PreparedClassPath prepared = PreparedClassPath.prepare(configuration)) {
            assertThat(prepared.entries()).containsExactly(first, alias.resolve(hidden.getFileName()),
                    alias.resolve(a.getFileName()), alias.resolve("nested").resolve(deep.getFileName()),
                    alias.resolve(z.getFileName()), z, last);
        }
    }

    @Test
    void rejectsNoMatchesMissingRootsNonDirectoriesAndNonHostPaths() throws Exception {
        assertThatThrownBy(() -> prepareGlob(temporary, "**/*.jar", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("**/*.jar")
                .hasMessageContaining(temporary.toString());
        assertThatThrownBy(() -> prepareGlob(temporary.resolve("missing"), "*", false))
                .isInstanceOf(NoSuchFileException.class);
        Path file = jar(temporary.resolve("file.jar"), null, false);
        assertThatThrownBy(() -> prepareGlob(file, "*", false)).isInstanceOf(NotDirectoryException.class);
        URI uri = URI.create("jar:" + temporary.resolve("provider.zip").toUri());
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder()
                    .addPath(filesystem.getPath("/")).build()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host filesystem");
        }
    }

    @Test
    void skipsDiscoveredLinksByDefaultAndFollowsAliasesWhenEnabled() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path keep = jar(root.resolve("keep.jar"), null, false);
        Path external = jar(outside.resolve("external.jar"), null, false);
        Path fileAlias = Files.createSymbolicLink(root.resolve("alias.jar"), external);
        Path directoryAlias = Files.createSymbolicLink(root.resolve("alias-directory"), outside);
        try (PreparedClassPath prepared = prepareGlob(root, "**/*.jar", false)) {
            assertThat(prepared.entries()).containsExactly(keep);
        }
        try (PreparedClassPath prepared = prepareGlob(root, "**/*.jar", true)) {
            assertThat(prepared.entries()).containsExactly(directoryAlias.resolve("external.jar"), fileAlias, keep);
        }
        Path broken = Files.createSymbolicLink(root.resolve("broken"), temporary.resolve("missing"));
        assertThatThrownBy(() -> prepareGlob(root, "*.jar", true))
                .isInstanceOf(IOException.class).hasMessageContaining("broken");
        try (PreparedClassPath prepared = prepareGlob(root, "*.jar", false)) {
            assertThat(prepared.entries()).containsExactly(keep);
        }
        Files.delete(broken);
        Files.createSymbolicLink(root.resolve("cycle"), root);
        assertThatThrownBy(() -> prepareGlob(root, "*.jar", true)).isInstanceOf(FileSystemLoopException.class);
    }

    @Test
    void explicitDirectoryAliasesAreCopiedButDiscoveredLinksRequireOptIn() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("resource.txt"), "outside");
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("ordinary.txt"), "ordinary");
        Path alias = Files.createSymbolicLink(temporary.resolve("explicit"), source);
        try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(alias).copy(true).build())) {
            assertThat(Files.readString(prepared.entries().getFirst().resolve("ordinary.txt"))).isEqualTo("ordinary");
        }
        Files.createSymbolicLink(source.resolve("linked"), outside);
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(alias).copy(true).build()))
                .isInstanceOf(IOException.class).hasMessageContaining("symbolic link");
        try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(alias).copy(true).followLinks(true).build())) {
            assertThat(Files.readString(prepared.entries().getFirst().resolve("linked/resource.txt")))
                    .isEqualTo("outside");
        }
        Path broken = Files.createSymbolicLink(source.resolve("broken"), temporary.resolve("missing"));
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(alias).copy(true).followLinks(true).build()))
                .isInstanceOf(IOException.class).hasMessageContaining("broken");
        Files.delete(broken);
        Files.createSymbolicLink(source.resolve("cycle"), source);
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(alias).copy(true).followLinks(true).build())).isInstanceOf(FileSystemLoopException.class);
    }

    @Test
    void reportsPresentCorruptArchivesIncludingManifestTargetsAndCleansPartialCopies() throws Exception {
        Path corrupt = Files.writeString(temporary.resolve("corrupt.jar"), "not a zip archive");
        Path root = jar(temporary.resolve("root.jar"), "corrupt.jar", true);
        Set<Path> before = captureDirectories();
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(root).copy(true).build()))
                .isInstanceOf(IOException.class).hasMessageContaining(corrupt.toString());
        assertThat(captureDirectories()).isEqualTo(before);
        assertThat(root).exists();
        assertThat(corrupt).exists();
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(corrupt).build()))
                .isInstanceOf(IOException.class).hasMessageContaining(corrupt.toString());
    }

    @Test
    void rejectsNonRegularEntriesAndCopiedResourcesButSkipsThemDuringSelection() throws Exception {
        Path device = Path.of("/dev/null");
        assumeTrue(Files.exists(device) && !Files.isRegularFile(device));
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(device).build()))
                .isInstanceOf(IOException.class).hasMessageContaining("not a directory or regular JAR");
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path keep = jar(root.resolve("keep.jar"), null, false);
        Files.createSymbolicLink(root.resolve("device.jar"), device);
        try (PreparedClassPath prepared = prepareGlob(root, "*.jar", true)) {
            assertThat(prepared.entries()).containsExactly(keep);
        }
        assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder()
                .addPath(root).copy(true).followLinks(true).build()))
                .isInstanceOf(IOException.class).hasMessageContaining("not a regular file");
    }

    @Test
    void unreadablePresentDependenciesFailInsteadOfBeingIgnored() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path unreadable = jar(temporary.resolve("unreadable.jar"), null, false);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(unreadable);
        Files.setPosixFilePermissions(unreadable, Set.of());
        try {
            assumeFalse(Files.isReadable(unreadable), "Current user bypasses file permissions");
            Path root = jar(temporary.resolve("root.jar"), "unreadable.jar", true);
            assertThatThrownBy(() -> PreparedClassPath.prepare(TraceClassPath.builder().addPath(root).build()))
                    .isInstanceOf(IOException.class).hasMessageContaining(unreadable.toString());
        } finally {
            Files.setPosixFilePermissions(unreadable, permissions);
        }
    }

    @Test
    void copyingAnAncestorOfTheCaptureTreeSkipsItsOwnOutputIncludingFollowedAliases() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("resource.txt"), "original");
        Path capture = Files.createDirectory(source.resolve("capture"));
        Path target = capture.resolve("entry-0");
        Files.createSymbolicLink(source.resolve("capture-alias"), capture);
        ClassPathFiles.copyDirectory(source, target, true);
        assertThat(Files.readString(target.resolve("resource.txt"))).isEqualTo("original");
        assertThat(target.resolve("capture")).doesNotExist();
        assertThat(target.resolve("capture-alias")).doesNotExist();
    }

    @Test
    void manifestReferencesUseAnExplicitAliasLocationInBorrowedAndCopiedModes() throws Exception {
        Path originalDirectory = Files.createDirectory(temporary.resolve("original"));
        Path encounteredDirectory = Files.createDirectory(temporary.resolve("encountered"));
        Path original = jar(originalDirectory.resolve("library.jar"), "dependency.jar", true);
        jar(originalDirectory.resolve("dependency.jar"), "missing.jar", true);
        Path encounteredDependency = jar(encounteredDirectory.resolve("dependency.jar"), null, false);
        Path alias = Files.createSymbolicLink(encounteredDirectory.resolve("alias.jar"), original);
        for (boolean copy : new boolean[] { false, true }) {
            try (PreparedClassPath prepared = PreparedClassPath.prepare(TraceClassPath.builder()
                    .addPath(alias).copy(copy).build())) {
                assertThat(prepared.entries()).hasSize(2);
                assertThat(Files.readAllBytes(prepared.entries().getLast()))
                        .isEqualTo(Files.readAllBytes(encounteredDependency));
            }
        }
    }

    private PreparedClassPath prepareGlob(Path root, String glob, boolean followLinks) throws IOException {
        return PreparedClassPath.prepare(TraceClassPath.builder().addGlob(root, glob).followLinks(followLinks).build());
    }

    private Path jar(Path file, String classPath, boolean withManifest) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        try (JarOutputStream output = withManifest
                ? new JarOutputStream(Files.newOutputStream(file), manifest)
                : new JarOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new JarEntry("resource.txt"));
            output.write(file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return file;
    }

    private Set<Path> captureDirectories() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("tracer-class-path-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

}
