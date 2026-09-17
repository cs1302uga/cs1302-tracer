package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceFallbackTest {
    private static class ResourceLoader extends ClassLoader {
        private final String mode;
        ResourceLoader(String mode) { super(ResourceFallbackTest.class.getClassLoader()); this.mode = mode; }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("cs1302.tracer.App")
                    && !name.equals("cs1302.tracer.App$PropertiesVersionProvider")
                    && !name.equals("cs1302.tracer.LicenseHelper")) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = stream.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length, App.class.getProtectionDomain());
                } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.endsWith("version.properties") || name.endsWith("THIRD-PARTY.txt")) {
                if (mode.equals("missing")) return null;
                if (mode.equals("unreadable") || mode.equals("both-failures")) return new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("unreadable resource"); }
                    @Override public void close() throws IOException {
                        if (mode.equals("both-failures")) throw new IOException("close failed");
                    }
                };
                return new ByteArrayInputStream((mode.equals("empty") ? "" : "version=test-version\n")
                        .getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() throws IOException {
                        if (mode.equals("close-failure")) throw new IOException("close failed");
                    }
                };
            }
            return super.getResourceAsStream(name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "unreadable", "valid", "close-failure", "both-failures"})
    void versionFallsBackWhenResourceCannotProvideAVersion(String mode) throws Exception {
        var type = new ResourceLoader(mode).loadClass("cs1302.tracer.App$PropertiesVersionProvider");
        String[] version = (String[]) type.getMethod("getVersion").invoke(type.getConstructor().newInstance());
        assertThat(version).containsExactly(mode.equals("valid") ? "test-version" : "development");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "unreadable", "valid", "close-failure", "both-failures"})
    void licenseFallbackIsAvailableWithoutPackagedNotices(String mode) throws Exception {
        var type = new ResourceLoader(mode).loadClass("cs1302.tracer.LicenseHelper");
        String notice = (String) type.getMethod("getThirdPartyNotices").invoke(null);
        if (mode.equals("valid")) assertThat(notice).isEqualTo("version=test-version");
        else assertThat(notice).contains("third-party dependencies", "JavaParser", "Gson", "picocli");
    }
}
