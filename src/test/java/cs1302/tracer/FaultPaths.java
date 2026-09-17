package cs1302.tracer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.net.URI;
import java.util.*;

/** Real paths with deterministic deletion failures, independent of user permissions. */
final class FaultPaths {
    static Path refusingDeletion(Path real) {
        var provider = new Provider(real.getFileSystem().provider());
        return provider.wrap(real);
    }

    private static final class Provider extends FileSystemProvider {
        private final FileSystemProvider delegate;
        private final Map<Path, Path> originals = new IdentityHashMap<>();
        private final FileSystem fs;

        Provider(FileSystemProvider delegate) {
            this.delegate = delegate;
            fs = new FileSystem() {
                @Override public FileSystemProvider provider() { return Provider.this; }
                @Override public void close() {}
                @Override public boolean isOpen() { return true; }
                @Override public boolean isReadOnly() { return false; }
                @Override public String getSeparator() { return FileSystems.getDefault().getSeparator(); }
                @Override public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }
                @Override public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }
                @Override public Set<String> supportedFileAttributeViews() { return Set.of("basic"); }
                @Override public Path getPath(String first, String... more) { throw new UnsupportedOperationException(); }
                @Override public PathMatcher getPathMatcher(String pattern) { throw new UnsupportedOperationException(); }
                @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
                @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
            };
        }

        Path unwrap(Path path) { return originals.getOrDefault(path, path); }
        Path wrap(Path path) {
            Path wrapped = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class},
                    (self, method, args) -> {
                        if (method.getName().equals("getFileSystem")) return fs;
                        if (method.getName().equals("equals")) return self == args[0];
                        if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                        Object[] actual = args == null ? null : args.clone();
                        if (actual != null) for (int i = 0; i < actual.length; i++) {
                            if (actual[i] instanceof Path p) actual[i] = unwrap(p);
                        }
                        try {
                            Object result = method.invoke(path, actual);
                            return result instanceof Path p ? wrap(p) : result;
                        } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            originals.put(wrapped, path);
            return wrapped;
        }
        @Override public String getScheme() { return "fault"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { return fs; }
        @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel newByteChannel(Path p, Set<? extends OpenOption> o, FileAttribute<?>... a)
                throws IOException { return delegate.newByteChannel(unwrap(p), o, a); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path p, DirectoryStream.Filter<? super Path> filter)
                throws IOException {
            var stream = delegate.newDirectoryStream(unwrap(p), entry -> filter.accept(wrap(entry)));
            return new DirectoryStream<>() {
                @Override public Iterator<Path> iterator() {
                    var entries = stream.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return entries.hasNext(); }
                        @Override public Path next() { return wrap(entries.next()); }
                    };
                }
                @Override public void close() throws IOException { stream.close(); }
            };
        }
        @Override public void createDirectory(Path p, FileAttribute<?>... attrs) throws IOException { delegate.createDirectory(unwrap(p), attrs); }
        @Override public void delete(Path p) throws IOException { throw new AccessDeniedException(unwrap(p).toString()); }
        @Override public void copy(Path a, Path b, CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public void move(Path a, Path b, CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public boolean isSameFile(Path a, Path b) throws IOException { return delegate.isSameFile(unwrap(a), unwrap(b)); }
        @Override public boolean isHidden(Path p) throws IOException { return delegate.isHidden(unwrap(p)); }
        @Override public FileStore getFileStore(Path p) throws IOException { return delegate.getFileStore(unwrap(p)); }
        @Override public void checkAccess(Path p, AccessMode... modes) throws IOException { delegate.checkAccess(unwrap(p), modes); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path p, Class<V> type, LinkOption... options) {
            return delegate.getFileAttributeView(unwrap(p), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path p, Class<A> type, LinkOption... options)
                throws IOException { return delegate.readAttributes(unwrap(p), type, options); }
        @Override public Map<String, Object> readAttributes(Path p, String attrs, LinkOption... options)
                throws IOException { return delegate.readAttributes(unwrap(p), attrs, options); }
        @Override public void setAttribute(Path p, String attr, Object value, LinkOption... options)
                throws IOException { delegate.setAttribute(unwrap(p), attr, value, options); }
    }
}
