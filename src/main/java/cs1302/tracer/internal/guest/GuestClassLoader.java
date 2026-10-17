package cs1302.tracer.internal.guest;

import org.apiguardian.api.API;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads one prepared application class path without expanding JAR manifests again.
 *
 * <p>Platform classes retain parent precedence. JDK archive readers verify signatures and select the running
 * JDK's multi-release view; this loader preserves package metadata, sealing, and signer consistency. The inherited
 * URL search path is empty, so no implicit manifest dependencies can bypass prepared copies. Instances belong to
 * one job and must be closed after its threads have finished.</p>
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public class GuestClassLoader extends URLClassLoader {

    private final List<Entry> entries = new ArrayList<>();
    private boolean closed;

    /**
     * Opens exactly the supplied locations, in order.
     *
     * @param paths Compiled output followed by resolved dependencies.
     * @throws IOException If an archive cannot be opened.
     */
    public GuestClassLoader(List<Path> paths) throws IOException {
        this(paths, path -> new JarFile(path.toFile(), true, JarFile.OPEN_READ, Runtime.version()));
    } // GuestClassLoader

    /** Boundary for opening verified, version-selected archives. */
    @FunctionalInterface
    interface ArchiveOpener {

        /**
         * Opens one archive whose lifetime transfers to the loader.
         * @param path Prepared archive path.
         * @return Open archive reader.
         * @throws IOException If opening fails.
         */
        JarFile open(Path path) throws IOException;

    } // ArchiveOpener

    /**
     * Opens entries through the supplied archive I/O boundary.
     * @param paths Ordered prepared locations.
     * @param opener Archive reader factory.
     * @throws IOException If opening fails, with cleanup failures suppressed.
     */
    GuestClassLoader(List<Path> paths, ArchiveOpener opener) throws IOException {
        super(new URL[0], ClassLoader.getPlatformClassLoader());
        try {
            for (Path supplied : List.copyOf(paths)) {
                Path path = supplied.toAbsolutePath().normalize();
                URL location = path.toUri().toURL();
                JarFile archive = Files.isDirectory(path) ? null
                    : opener.open(path);
                this.entries.add(new Entry(path, location, archive));
            } // for
        } catch (IOException failure) {
            try {
                closeArchives();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } // try
            throw failure;
        } // try
    } // GuestClassLoader

    /** One literal directory or opened archive; a null archive identifies a directory. */
    private record Entry(Path path, URL location, JarFile archive) {} // Entry

    /** {@inheritDoc} */
    @Override
    public URL[] getURLs() {
        return this.entries.stream().map(Entry::location).toArray(URL[]::new);
    } // getURLs

    /** {@inheritDoc} */
    @Override
    protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
        if (this.closed) {
            throw new ClassNotFoundException(name);
        } // if
        String resource = name.replace('.', '/') + ".class";
        for (Entry entry : this.entries) {
            try {
                if (entry.archive() == null) {
                    Path file = directoryResource(entry.path(), resource);
                    if (file != null) {
                        defineApplicationPackage(name, null, entry.location());
                        byte[] bytes = Files.readAllBytes(file);
                        return defineClass(name, bytes, 0, bytes.length,
                            new CodeSource(entry.location(), (java.security.CodeSigner[]) null));
                    } // if
                } else {
                    JarEntry member = entry.archive().getJarEntry(resource);
                    if (member != null) {
                        byte[] bytes;
                        try (InputStream stream = entry.archive().getInputStream(member)) {
                            bytes = stream.readAllBytes();
                        } // try
                        defineApplicationPackage(name, entry.archive().getManifest(), entry.location());
                        return defineClass(name, bytes, 0, bytes.length,
                            new CodeSource(entry.location(), member.getCodeSigners()));
                    } // if
                } // if
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            } // try
        } // for
        throw new ClassNotFoundException(name);
    } // findClass

    /**
     * Defines metadata for a class's package and checks sealing before defining another class.
     *
     * @param className Binary class name.
     * @param manifest Originating manifest, or null for a directory or manifest-free archive.
     * @param origin Origin URL used for sealing and code sources.
     */
    private void defineApplicationPackage(String className, Manifest manifest, URL origin) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        } // if
        String name = className.substring(0, dot);
        Package existing = getDefinedPackage(name);
        if (existing == null) {
            if (manifest == null) {
                definePackage(name, null, null, null, null, null, null, null);
            } else {
                definePackage(name, manifest, origin);
            } // if
        } else if (existing.isSealed()) {
            if (!existing.isSealed(origin)) {
                throw new SecurityException("Sealing violation for package " + name);
            } // if
        } else if (isSealed(name, manifest)) { // if
            throw new SecurityException("Cannot seal already loaded package " + name);
        } // if
    } // defineApplicationPackage

    /**
     * Determines whether the manifest seals a package, honoring package-specific overrides.
     *
     * @param name Package name.
     * @param manifest Manifest, or null.
     * @return Whether the package is sealed.
     */
    private static boolean isSealed(String name, Manifest manifest) {
        if (manifest == null) {
            return false;
        } // if
        Attributes attributes = manifest.getAttributes(name.replace('.', '/') + "/");
        String sealed = attributes == null ? null : attributes.getValue(Attributes.Name.SEALED);
        if (sealed == null) {
            sealed = manifest.getMainAttributes().getValue(Attributes.Name.SEALED);
        } // if
        return Boolean.parseBoolean(sealed);
    } // isSealed

    /** {@inheritDoc} */
    @Override
    public synchronized URL findResource(String name) {
        if (!this.closed) {
            for (Entry entry : this.entries) {
                URL resource = resource(entry, name);
                if (resource != null) {
                    return resource;
                } // if
            } // for
        } // if
        return null;
    } // findResource

    /** {@inheritDoc} */
    @Override
    public synchronized Enumeration<URL> findResources(String name) {
        List<URL> resources = new ArrayList<>();
        if (!this.closed) {
            for (Entry entry : this.entries) {
                URL resource = resource(entry, name);
                if (resource != null) {
                    resources.add(resource);
                } // if
            } // for
        } // if
        return Collections.enumeration(resources);
    } // findResources

    /**
     * Locates a resource in one entry, preserving the selected multi-release member name.
     *
     * @param entry Search entry.
     * @param name Resource name.
     * @return Resource URL, or null if absent.
     */
    private static URL resource(Entry entry, String name) {
        try {
            if (entry.archive() == null) {
                Path file = directoryResource(entry.path(), name);
                return file == null ? null : file.toUri().toURL();
            } // if
            JarEntry member = entry.archive().getJarEntry(name);
            if (member == null) {
                return null;
            } // if
            String encoded = new URI(null, null, "/" + member.getRealName(), null).toASCIIString();
            // JarURLConnection splits at the first !/, including one inside an otherwise valid file URI.
            String archive = entry.location().toExternalForm().replace("!", "%21");
            return URL.of(URI.create("jar:" + archive + "!" + encoded), new UncachedJarHandler());
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalArgumentException("Cannot locate resource " + name, failure);
        } // try
    } // resource

    /**
     * Resolves a resource without permitting lexical traversal beyond the directory entry.
     *
     * @param root Search root.
     * @param name Resource name.
     * @return Existing resource, or null.
     */
    private static Path directoryResource(Path root, String name) {
        Path path = root.resolve(name).normalize();
        return path.startsWith(root) && Files.exists(path) ? path : null;
    } // directoryResource

    /** Opens resource connections without the JVM-wide JAR cache, so closing streams releases their readers. */
    private static final class UncachedJarHandler extends URLStreamHandler {

        /** {@inheritDoc} */
        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            URLConnection connection = URI.create(url.toExternalForm()).toURL().openConnection();
            connection.setUseCaches(false);
            return connection;
        } // openConnection

    } // UncachedJarHandler

    /** {@inheritDoc} */
    @Override
    public final synchronized void close() throws IOException {
        this.closed = true;
        IOException failure = null;
        try {
            closeArchives();
        } catch (IOException problem) {
            failure = problem;
        } // try
        try {
            super.close();
        } catch (IOException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            } // if
        } // try
        if (failure != null) {
            throw failure;
        } // if
    } // close

    /**
     * Releases every opened archive, including after a partially completed construction.
     *
     * @throws IOException If one or more readers fail to close.
     */
    private void closeArchives() throws IOException {
        IOException failure = null;
        for (Entry entry : this.entries) {
            if (entry.archive() != null) {
                try {
                    entry.archive().close();
                } catch (IOException problem) {
                    if (failure == null) {
                        failure = problem;
                    } else {
                        failure.addSuppressed(problem);
                    } // if
                } // try
            } // if
        } // for
        if (failure != null) {
            throw failure;
        } // if
    } // closeArchives

} // GuestClassLoader
