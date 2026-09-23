package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import cs1302.tracer.model.SourceMetadata;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceMetadataTest {

    private final PyTutorSerializer pytutor = new PyTutorSerializer(false, false, false);
    private final ModernTraceSerializer modern = new ModernTraceSerializer(false, false, false);

    @Test
    void reusesParsedMetadataAcrossSnapshotTraces() {
        String code = "public class Main {}";
        SourceMetadata metadata = SourceMetadata.from(code);
        ExecutionSnapshot snapshot = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(), new byte[0], new byte[0]);
        var first = pytutor.createTrace(code, "input", snapshot, metadata);
        var second = pytutor.createTrace(code, null, snapshot, metadata);
        assertThat(first.sources()).isSameAs(metadata.sources());
        assertThat(second.sources()).isSameAs(first.sources());
        assertThat(first.entryFile()).isEqualTo(metadata.entryFile());
        assertThat(second.entryFile()).isEqualTo(first.entryFile());
        assertThat(first).isEqualTo(pytutor.createTrace(code, "input", snapshot));
        assertThat(second).isEqualTo(pytutor.createTrace(code, "", snapshot));
    }

    @Test
    void rejectsAmbiguousDebugPathsInsteadOfLosingSourceFiles() {
        String code = "// --- a/Common.java ---\nclass A {}\n"
                + "// --- b/Common.java ---\nclass B {}\n";
        assertThatThrownBy(() -> pytutor.createTrace(code, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
        assertThatThrownBy(() -> modern.createTrace(code, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
    }

    @Test
    void retainsEverySourceAndSelectsEntryPointWithoutSnapshots() {
        String unused = "package demo;\r\n\r\nclass Unused {}  \r\n";
        String main = "package demo;\npublic class Main {\n"
                + "  public static void main(String[] args) {}\n}\n\n";
        String code = "// --- Unused.java ---\r\n" + unused
                + "// --- src/demo/Main.java ---\n" + main;
        Map<String, String> expected = Map.of("demo/Unused.java", unused, "demo/Main.java", main);
        var py = pytutor.createTrace(code, List.of());
        var mo = modern.createTrace(code, List.of());
        var bp = modern.createBreakpointsTrace(code, Map.of());
        assertThat(py.code()).isEqualTo(code);
        assertThat(mo.code()).isEqualTo(code);
        assertThat(bp.code()).isEqualTo(code);
        assertThat(py.sources()).isEqualTo(expected);
        assertThat(mo.sources()).isEqualTo(expected);
        assertThat(bp.sources()).isEqualTo(expected);
        assertThat(py.entryFile()).isEqualTo("demo/Main.java");
        assertThat(mo.entryFile()).isEqualTo(py.entryFile());
        assertThat(bp.entryFile()).isEqualTo(py.entryFile());
        for (Object trace : List.of(py, mo, bp)) {
            JsonObject json = ModernTraceSerializer.getGson().toJsonTree(trace).getAsJsonObject();
            assertThat(json.getAsJsonObject("sources").size()).isEqualTo(2);
            assertThat(json.get("entryFile").getAsString()).isEqualTo("demo/Main.java");
        }
    }

    @Test
    void singleFileStepsAndFramesResolveToExactSource() {
        String code = "package demo;\r\npublic class Main {\r\n"
                + "  public static void main(String[] args) {}\r\n}\r\n";
        ExecutionSnapshot snapshot = new ExecutionSnapshot(
                List.of(new StackSnapshot("main", 3, List.of(), Optional.empty(),
                        Optional.of("demo/Main.java"))),
                List.of(), Map.of(), new byte[0], new byte[0], Optional.of("demo/Main.java"));
        var py = pytutor.createTrace(code, snapshot);
        var mo = modern.createTrace(code, snapshot);
        assertThat(py.sources()).containsExactlyEntriesOf(Map.of("demo/Main.java", code));
        assertThat(mo.sources()).isEqualTo(py.sources());
        assertThat(py.entryFile()).isEqualTo("demo/Main.java");
        assertThat(mo.entryFile()).isEqualTo(py.entryFile());
        assertThat(py.sources().get(py.trace().getFirst().file())).isEqualTo(code);
        assertThat(py.sources().get(py.trace().getFirst().stackToRender().getFirst().file()))
                .isEqualTo(code);
        assertThat(mo.sources().get(mo.steps().getFirst().file())).isEqualTo(code);
        assertThat(mo.sources().get(mo.steps().getFirst().callStack().getFirst().file()))
                .isEqualTo(code);
    }
}
