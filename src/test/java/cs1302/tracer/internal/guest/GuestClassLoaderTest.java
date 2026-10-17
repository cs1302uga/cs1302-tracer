package cs1302.tracer.internal.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URI;
import java.net.JarURLConnection;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuestClassLoaderTest {

    @TempDir
    Path directory;

    @Test
    void directoryAndArchiveLookupPreserveOrderResourcesAndPlatformPrecedence() throws Exception {
        Path first = compile("first", Map.of(
            "sample/First.java", "package sample; public class First { public static int value() { return 1; } }",
            "sample/Second.java", "package sample; public class Second {}",
            "Plain.java", "public class Plain {}"));
        Path second = compile("second", Map.of(
            "sample/First.java", "package sample; public class First { public static int value() { return 2; } }",
            "sample/Provider.java", "package sample; public class Provider implements Runnable {"
                + " public void run() {} }"));
        Files.writeString(first.resolve("resource.txt"), "first");
        Map<String, byte[]> extras = Map.of("resource.txt", bytes("second"),
            "META-INF/services/java.lang.Runnable", bytes("sample.Provider\n"), "space # λ.txt", bytes("special"));
        Files.createDirectory(directory.resolve("bang!"));
        Path archive = archive("bang!/second.jar", second, null, extras);
        try (GuestClassLoader loader = new GuestClassLoader(List.of(first, archive))) {
            assertThat(loader.getURLs()).containsExactly(first.toUri().toURL(), archive.toUri().toURL());
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(value(loader, "sample.First")).isEqualTo(1);
            assertThat(loader.loadClass("sample.Second").getPackage().isSealed()).isFalse();
            assertThat(loader.loadClass("Plain").getClassLoader()).isSameAs(loader);
            assertThatThrownBy(() -> loader.loadClass("Absent")).isInstanceOf(ClassNotFoundException.class);
            assertThat(read(loader.getResource("resource.txt"))).isEqualTo("first");
            List<URL> resources = Collections.list(loader.getResources("resource.txt"));
            assertThat(resources).hasSize(2);
            assertThat(read(resources.get(1))).isEqualTo("second");
            assertThat(resources.get(1).openConnection().getUseCaches()).isFalse();
            assertThat(read(loader.getResource("space # λ.txt"))).isEqualTo("special");
            assertThat(loader.findResource("absent")).isNull();
            assertThat(Collections.list(loader.findResources("absent"))).isEmpty();
            assertThat(loader.findResource("../outside.txt")).isNull();
            assertThat(loader.findResource("")).isNotNull();
            Class<?> provider = ServiceLoader.load(Runnable.class, loader).findFirst().orElseThrow().getClass();
            assertThat(provider.getName()).isEqualTo("sample.Provider");
            assertThat(provider.getProtectionDomain().getCodeSource().getLocation()).isEqualTo(archive.toUri().toURL());
        }
        GuestClassLoader closed = new GuestClassLoader(List.of(first, archive));
        closed.close();
        closed.close();
        assertThat(closed.findResource("resource.txt")).isNull();
        assertThat(Collections.list(closed.findResources("resource.txt"))).isEmpty();
        assertThatThrownBy(() -> closed.findClass("sample.First")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> new GuestClassLoader(List.of(archive, directory.resolve("missing.jar"))))
            .isInstanceOf(IOException.class);
        Files.createDirectories(first.resolve("sample/Broken.class"));
        try (GuestClassLoader loader = new GuestClassLoader(List.of(first))) {
            assertThatThrownBy(() -> loader.findClass("sample.Broken"))
                .isInstanceOf(ClassNotFoundException.class).hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void copiedArchivesNeverReexpandRelativeOrAbsoluteManifestReferences() throws Exception {
        Path dependencyClasses = compile("dependency", Map.of(
            "library/Value.java", "package library; public class Value { public static int value() { return 7; } }"));
        Path original = archive("original.jar", dependencyClasses, null, Map.of("resource.txt", bytes("captured")));
        Manifest manifest = manifest();
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, original.toUri() + " original.jar");
        Path owner = archive("owner.jar", null, manifest, Map.of());
        Path copy = directory.resolve("copy.jar");
        Files.copy(original, copy);
        try (GuestClassLoader loader = new GuestClassLoader(List.of(owner))) {
            assertThatThrownBy(() -> loader.loadClass("library.Value")).isInstanceOf(ClassNotFoundException.class);
            assertThat(loader.findResource("resource.txt")).isNull();
        }
        Files.delete(original);
        try (GuestClassLoader loader = new GuestClassLoader(List.of(owner, copy))) {
            assertThat(value(loader, "library.Value")).isEqualTo(7);
            assertThat(read(loader.getResource("resource.txt"))).isEqualTo("captured");
        }
    }

    @Test
    void multiReleaseClassesAndResourcesUseTheRunningJdkView() throws Exception {
        Path base = compile("base", Map.of(
            "sample/Version.java", "package sample; public class Version { public static int value() { return 8; } }"));
        Path v21 = compile("v21", Map.of(
            "sample/Version.java", "package sample; public class Version { public static int value() { return 21; } }"));
        Path v25 = compile("v25", Map.of(
            "sample/Version.java", "package sample; public class Version { public static int value() { return 25; } }"));
        Manifest manifest = manifest();
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Files.createDirectory(directory.resolve("multi!"));
        Path archive = archive("multi!/multi.jar", base, manifest, Map.of(
            "META-INF/versions/21/sample/Version.class", Files.readAllBytes(v21.resolve("sample/Version.class")),
            "META-INF/versions/25/sample/Version.class", Files.readAllBytes(v25.resolve("sample/Version.class")),
            "version.txt", bytes("8"), "META-INF/versions/21/version.txt", bytes("21"),
            "META-INF/versions/25/version.txt", bytes("25")));
        int expected = Runtime.version().feature() >= 25 ? 25 : 21;
        try (GuestClassLoader loader = new GuestClassLoader(List.of(archive))) {
            assertThat(value(loader, "sample.Version")).isEqualTo(expected);
            URL resource = loader.getResource("version.txt");
            assertThat(resource.toString()).contains("META-INF/versions/" + expected + "/version.txt");
            assertThat(read(resource)).isEqualTo(Integer.toString(expected));
        }
    }

    @Test
    void packageMetadataAndSealingArePreserved() throws Exception {
        Path first = compile("sealed-first", Map.of(
            "sample/One.java", "package sample; public class One {}",
            "sample/Two.java", "package sample; public class Two {}"));
        Path other = compile("sealed-other", Map.of(
            "sample/Other.java", "package sample; public class Other {}"));
        Manifest sealed = manifest();
        sealed.getMainAttributes().putValue("Sealed", "true");
        sealed.getMainAttributes().putValue("Implementation-Version", "main-version");
        Attributes packageAttributes = new Attributes();
        packageAttributes.putValue("Implementation-Version", "package-version");
        sealed.getEntries().put("sample/", packageAttributes);
        Path sealedJar = archive("sealed.jar", first, sealed, Map.of());
        Path otherJar = archive("other.jar", other, manifest(), Map.of());
        try (GuestClassLoader loader = new GuestClassLoader(List.of(sealedJar, otherJar))) {
            Package pack = loader.loadClass("sample.One").getPackage();
            assertThat(pack.isSealed()).isTrue();
            assertThat(pack.getImplementationVersion()).isEqualTo("package-version");
            assertThat(loader.loadClass("sample.Two").getPackage()).isSameAs(pack);
            assertThatThrownBy(() -> loader.loadClass("sample.Other"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Sealing violation");
        }
        try (GuestClassLoader loader = new GuestClassLoader(List.of(other, sealedJar))) {
            loader.loadClass("sample.Other");
            assertThatThrownBy(() -> loader.loadClass("sample.One"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Cannot seal");
        }
        packageAttributes.putValue("Sealed", "false");
        Path override = archive("override.jar", first, sealed, Map.of());
        try (GuestClassLoader loader = new GuestClassLoader(List.of(otherJar, override))) {
            loader.loadClass("sample.Other");
            assertThat(loader.loadClass("sample.One").getPackage().isSealed()).isFalse();
            assertThat(loader.loadClass("sample.Two").getPackage().isSealed()).isFalse();
        }
        try (GuestClassLoader loader = new GuestClassLoader(List.of(otherJar, first))) {
            loader.loadClass("sample.Other");
            assertThat(loader.loadClass("sample.One").getPackage().isSealed()).isFalse();
        }
        Manifest defaultSealed = manifest();
        defaultSealed.getMainAttributes().putValue("Sealed", "true");
        Path defaultJar = archive("default-sealed.jar", first, defaultSealed, Map.of());
        try (GuestClassLoader loader = new GuestClassLoader(List.of(otherJar, defaultJar))) {
            loader.loadClass("sample.Other");
            assertThatThrownBy(() -> loader.loadClass("sample.One"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Cannot seal");
        }
    }

    @Test
    void signedClassesRetainSignersAndTamperingCannotFallThroughToLaterEntries() throws Exception {
        Path classes = compile("signed", Map.of(
            "signed/Value.java", "package signed; public class Value { public static int value() { return 1; } }"));
        Path unsignedClasses = compile("unsigned", Map.of("signed/Other.java", "package signed; public class Other {}"));
        Path signed = archive("signed.jar", classes, manifest(), Map.of());
        Path keystore = directory.resolve("fixture.p12");
        command("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=Tracer Test", "-validity", "1", "-keystore", keystore.toString(),
            "-storepass", "fixture-password", "-keypass", "fixture-password", "-noprompt");
        command("jarsigner", "-keystore", keystore.toString(), "-storepass", "fixture-password",
            signed.toString(), "fixture");
        try (GuestClassLoader loader = new GuestClassLoader(List.of(signed, unsignedClasses))) {
            Class<?> loaded = loader.loadClass("signed.Value");
            assertThat(loaded.getSigners()).isNotEmpty();
            assertThat(loaded.getProtectionDomain().getCodeSource().getCodeSigners()).hasSize(1);
            assertThatThrownBy(() -> loader.loadClass("signed.Other")).isInstanceOf(SecurityException.class);
        }
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(signed.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                try (InputStream stream = jar.getInputStream(entry)) {
                    members.put(entry.getName(), stream.readAllBytes());
                }
            }
        }
        byte[] altered = members.get("signed/Value.class").clone();
        altered[altered.length - 1] ^= 1;
        members.put("signed/Value.class", altered);
        Path tampered = archive("tampered.jar", null, null, members);
        try (GuestClassLoader loader = new GuestClassLoader(List.of(tampered, classes))) {
            assertThatThrownBy(() -> loader.loadClass("signed.Value")).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void descriptorsRoundTripLiteralPathsAndRejectInvalidFormats() throws Exception {
        Path literal = directory.resolve("path : ; \" '  λ.jar");
        Path descriptor = GuestClassPath.write(directory, List.of(literal));
        assertThat(GuestClassPath.read(descriptor)).containsExactly(directory, literal);
        var constructor = GuestClassPath.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        Path invalid = directory.resolve("invalid.bin");
        try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(invalid))) {
            stream.writeInt(0);
        }
        assertThatThrownBy(() -> GuestClassPath.read(invalid)).isInstanceOf(IOException.class)
            .hasMessageContaining("Unsupported");
        try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(invalid))) {
            stream.writeInt(0x43545031);
            stream.writeInt(0);
        }
        assertThatThrownBy(() -> GuestClassPath.read(invalid)).isInstanceOf(IOException.class)
            .hasMessageContaining("Empty");
        Files.write(descriptor, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> GuestClassPath.read(descriptor)).isInstanceOf(IOException.class)
            .hasMessageContaining("Trailing");
    }

    @Test
    void systemLoaderUsesTheDescriptorAndPlatformParent() throws Exception {
        Path classes = compile("system", Map.of("Main.java", "public class Main {}"));
        Path descriptor = GuestClassPath.write(classes, List.of());
        String property = GuestSystemClassLoader.DESCRIPTOR_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, descriptor.toString());
        try (GuestSystemClassLoader loader = new GuestSystemClassLoader(getClass().getClassLoader())) {
            assertThat(loader.getParent()).isSameAs(ClassLoader.getPlatformClassLoader());
            assertThat(loader.loadClass("Main").getClassLoader()).isSameAs(loader);
            assertThatThrownBy(() -> loader.loadClass(GuestClassLoader.class.getName()))
                .isInstanceOf(ClassNotFoundException.class);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void constructionPreservesOpeningFailureAndSuppressesCleanupFailure() throws Exception {
        Path archive = archive("partial.jar", null, null, Map.of());
        FailingJarFile opened = new FailingJarFile(archive, "close-first");
        IOException openingFailure = new IOException("open-second");
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new GuestClassLoader(List.of(archive, archive), path -> {
            if (calls.getAndIncrement() == 0) {
                return opened;
            }
            throw openingFailure;
        })).isSameAs(openingFailure);
        assertThat(opened.closed).isTrue();
        assertThat(openingFailure.getSuppressed()).containsExactly(opened.failure);
    }

    @Test
    void closeAttemptsEveryArchiveAndPreservesOrderedFailures() throws Exception {
        Path archive = archive("closing.jar", null, null, Map.of());
        FailingJarFile first = new FailingJarFile(archive, "first");
        FailingJarFile second = new FailingJarFile(archive, "second");
        AtomicInteger calls = new AtomicInteger();
        GuestClassLoader loader = new GuestClassLoader(List.of(archive, archive),
            path -> calls.getAndIncrement() == 0 ? first : second);
        assertThatThrownBy(loader::close).isSameAs(first.failure);
        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
        assertThat(first.failure.getSuppressed()).containsExactly(second.failure);
        assertThat(loader.findResource("anything")).isNull();
    }

    @Test
    void closeAlsoReleasesInheritedResourceReadersAndKeepsTheOriginalFailure() throws Exception {
        Path archive = archive("resource-closing.jar", null, null, Map.of());
        for (boolean failArchive : List.of(false, true)) {
            FailingJarFile inherited = new FailingJarFile(archive, "resource reader close");
            URL resource = URL.of(URI.create("jar:file:/tracked.jar!/resource"), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    return new JarURLConnection(url) {
                        @Override
                        public void connect() {}

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(new byte[] {1});
                        }

                        @Override
                        public JarFile getJarFile() {
                            return inherited;
                        }
                    };
                }
            });
            FailingJarFile failing = new FailingJarFile(archive, "archive close");
            GuestClassLoader loader = new GuestClassLoader(List.of(archive), path ->
                    failArchive ? failing : new JarFile(path.toFile())) {
                @Override
                public URL findResource(String name) {
                    return resource;
                }
            };
            InputStream trackedStream = loader.getResourceAsStream("tracked-resource");
            assertThat(trackedStream.read()).isEqualTo(1);
            if (failArchive) {
                assertThatThrownBy(loader::close).isSameAs(failing.failure);
                assertThat(failing.failure.getSuppressed()).containsExactly(inherited.failure);
            } else {
                assertThatThrownBy(loader::close).isSameAs(inherited.failure);
                // This fixture was not transferred to the loader in the resource-only case.
                assertThatThrownBy(failing::close).isSameAs(failing.failure);
            }
            assertThat(inherited.closed).isTrue();
            java.lang.ref.Reference.reachabilityFence(trackedStream);
        }
    }

    @Test
    void malformedResourceUrlReportsTheRequestedEntry() throws Exception {
        Path archive = archive("invalid-resource.jar", null, null, Map.of("/[broken", bytes("invalid")));
        try (GuestClassLoader loader = new GuestClassLoader(List.of(archive))) {
            assertThatThrownBy(() -> loader.findResource("/[broken"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/[broken")
                .hasCauseInstanceOf(java.net.URISyntaxException.class);
        }
    }

    private static final class FailingJarFile extends JarFile {

        final IOException failure;
        boolean closed;

        FailingJarFile(Path path, String message) throws IOException {
            super(path.toFile());
            failure = new IOException(message);
        }

        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
            throw failure;
        }

    }

    private Path compile(String name, Map<String, String> sources) throws Exception {
        Path root = Files.createDirectories(directory.resolve(name));
        Path output = Files.createDirectories(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-g", "--release", "21", "-d", output.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
            .isZero();
        return output;
    }

    private Path archive(String name, Path classes, Manifest manifest, Map<String, byte[]> extras) throws Exception {
        Path path = directory.resolve(name);
        try (JarOutputStream stream = manifest == null ? new JarOutputStream(Files.newOutputStream(path))
                : new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (classes != null) {
                try (var paths = Files.walk(classes)) {
                    for (Path file : paths.filter(Files::isRegularFile).toList()) {
                        stream.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, stream);
                        stream.closeEntry();
                    }
                }
            }
            for (Map.Entry<String, byte[]> extra : extras.entrySet()) {
                stream.putNextEntry(new JarEntry(extra.getKey()));
                stream.write(extra.getValue());
                stream.closeEntry();
            }
        }
        return path;
    }

    private static Manifest manifest() {
        Manifest result = new Manifest();
        result.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return result;
    }

    private static Object value(ClassLoader loader, String name) throws Exception {
        return loader.loadClass(name).getMethod("value").invoke(null);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(URL url) throws Exception {
        try (InputStream stream = url.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void command(String name, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", name).toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

}
