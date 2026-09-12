package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.TraceValue;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HardeningRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"../Escape.java", "..\\Escape.java", "C:/Escape.java", "/Escape.java"})
    void rejectsEscapingSourcePaths(String path) {
        assertThatThrownBy(() -> CompilationHelper.compile("// --- " + path
                + " ---\npublic class Escape { public static void main(String[] args) {} }"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source path");
    }

    @Test
    void validatesAllPathsBeforeWriting(@TempDir Path directory) {
        Path target = directory.resolve("Owned.java");
        String source = "// --- " + target + " ---\npublic class Owned {}\n"
                + "// --- Main.java ---\npublic class Main { public static void main(String[] a) {} }";
        assertThatThrownBy(() -> CompilationHelper.compile(source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    void rejectsNormalizedDuplicates() {
        String source = "// --- Main.java ---\npublic class Main { public static void main(String[] a) {} }\n"
                + "// --- sub/../Main.java ---\npublic class Main {}";
        assertThatThrownBy(() -> CompilationHelper.compile(source))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void failedCompilationsCleanTemporaryFiles() throws Exception {
        Set<Path> before = compilationDirectories();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> CompilationHelper.compile(
                    "public class Bad { public static void main(String[] a) { int x = false; } }"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CompilationHelper.compile("public class NoMain {}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(compilationDirectories()).isEqualTo(before);
    }

    private Set<Path> compilationDirectories() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("code-tracer"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void tracesNullMapKeysAndValuesInBothFormats() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        java.util.HashMap<String,String> map = new java.util.HashMap<>();
                        map.put("key", null);
                        map.put(null, "value");
                        System.out.println("done");
                    }
                }
                """;
        try (var compiled = CompilationHelper.compile(source)) {
            var snapshot = DebugTraceHelper.trace(compiled, List.of(StaticJavaParser.parse(source)));
            var map = snapshot.heap().values().stream().filter(v -> v instanceof TraceValue.Map)
                    .map(v -> (TraceValue.Map) v).findFirst().orElseThrow();
            assertThat(map.value().keySet()).anyMatch(v -> v instanceof TraceValue.Null);
            assertThat(map.value().values()).anyMatch(v -> v instanceof TraceValue.Null);
            String modern = ModernTraceSerializer.getGson().toJson(
                    new ModernTraceSerializer(false, false, false).createTrace(source, snapshot));
            String legacy = new PyTutorSerializer(false, false, false).serialize(source, snapshot, false);
            assertThat(modern).contains("null");
            assertThat(legacy).contains("null");
        }
    }
}
