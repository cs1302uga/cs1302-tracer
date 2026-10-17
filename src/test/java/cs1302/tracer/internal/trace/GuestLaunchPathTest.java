package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cs1302.tracer.internal.CompilationHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuestLaunchPathTest {
    @TempDir
    Path directory;

    @Test
    void utilityConstructorIsPrivate() throws Exception {
        var constructor = GuestLauncher.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void malformedImplementationLocationReportsAnIoFailure() throws Exception {
        java.net.URL location = java.net.URL.of(java.net.URI.create("fixture:/valid"), new java.net.URLStreamHandler() {
            @Override
            protected java.net.URLConnection openConnection(java.net.URL url) {
                throw new AssertionError("A code-source location must not be opened");
            }

            @Override
            protected String toExternalForm(java.net.URL url) {
                return "file:/invalid location";
            }
        });
        assertThatThrownBy(() -> GuestLauncher.loaderLocation(location))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("Invalid guest loader location")
            .hasCauseInstanceOf(java.net.URISyntaxException.class);
    }

    @Test
    void launchesFromAClasspathContainingSpacesAndQuotes() throws Exception {
        try (var original = CompilationHelper
                .compile("public class Main { public static void main(String[] args) { int value = 1; } }")) {
            Path destination = directory.resolve(" space \" double ' single");
            Files.move(original.classPath(), destination);
            try (var relocated = new CompilationHelper.CompilationResult(destination, original.compiledClassNames(),
                    original.mainClass(), original.sourceRoot(), original.previewEnabled())) {
                assertThat(
                        DebugTraceHelper.traceChronological(relocated, java.util.List.of(), java.util.List.of(), true))
                        .isNotEmpty();
            }
        }
    }
}
