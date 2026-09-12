package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import cs1302.tracer.CompilationHelper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BreakpointReaderTest {
    @Test
    void discoversUnusedNestedAndRecordClassesWithoutExecutingGuest(@TempDir Path directory)
            throws Exception {
        Path marker = directory.resolve("executed");
        String source = """
                // --- sample/Main.java ---
                package sample;
                public class Main {
                    static { touch(); }
                    static void touch() {
                        try { java.nio.file.Files.writeString(java.nio.file.Path.of("%s"), "ran"); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                    public static void main(String[] args) { while (true) {} }
                    static class Unused { int answer() { return 42; } }
                }
                // --- sample/Pair.java ---
                package sample;
                public record Pair(int x, int y) {
                    public int sum() { return x + y; }
                }
                """.formatted(marker.toString().replace("\\", "\\\\"));
        try (var compiled = CompilationHelper.compile(source)) {
            var locations = DebugTraceHelper.getValidBreakpointLinesByFile(compiled);
            assertThat(locations).containsKeys("sample/Main.java", "sample/Pair.java");
            assertThat(locations.get("sample/Main.java")).contains(3, 9);
            assertThat(locations.get("sample/Pair.java")).contains(3);
            assertThat(marker).doesNotExist();
        }
    }
}
