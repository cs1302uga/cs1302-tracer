package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.model.SourceMetadata;
import cs1302.tracer.internal.serialize.ModernTraceSerializer;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubmittedSourcesTest {

    private static final String MAIN = "package demo;\r\npublic class Main {\r\n"
            + "    public static void main(String[] args) {}\r\n}\r\n";
    private static final String UNUSED = "package demo;\nclass Unused {}  \n";
    private static final String CODE = "// --- source/Unused.java ---\n" + UNUSED
            + "// --- src/demo/Main.java ---\r\n" + MAIN;

    @Test
    void detachedSourcesPreserveExactTextAndDistinctIdentitiesAfterPreparationCloses() throws Exception {
        SubmittedSources submitted;
        Path output;
        try (var prepared = SourcePreparation.prepare(CODE, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            submitted = prepared.submitted();
            output = prepared.compilation().classPath();
            assertThat(output).exists();
        }
        assertThat(output).doesNotExist();
        assertThat(submitted.code()).isEqualTo(CODE);
        assertThat(submitted.files()).extracting(SubmittedSources.File::logicalPath)
                .containsExactly("source/Unused.java", "src/demo/Main.java");
        assertThat(submitted.files()).extracting(SubmittedSources.File::debugPath)
                .containsExactly("demo/Unused.java", "demo/Main.java");
        assertThat(submitted.files().getLast().relativePath())
                .isEqualTo(Path.of("src", "demo", "Main.java").toString());
        assertThat(submitted.files().getLast().content()).isEqualTo(MAIN);
        SourceMetadata metadata = submitted.metadata();
        assertThat(metadata.sources()).hasSize(2).containsEntry("demo/Unused.java", UNUSED)
                .containsEntry("demo/Main.java", MAIN);
        assertThat(metadata.sources().keySet()).containsExactly("demo/Unused.java", "demo/Main.java");
        assertThat(metadata.entryFile()).isEqualTo("demo/Main.java");
        assertThatThrownBy(() -> submitted.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metadata.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compilerOwnedAstChangesCannotMutateDetachedDescription() {
        var parsed = CompilationHelper.parseSources(CODE);
        SubmittedSources submitted = parsed.submitted();
        parsed.files().getLast().ast().setPackageDeclaration("different");
        assertThat(parsed.submitted()).isSameAs(submitted);
        assertThat(submitted.metadata().entryFile()).isEqualTo("demo/Main.java");
        assertThat(submitted.files().getLast().debugPath()).isEqualTo("demo/Main.java");
        assertThat(submitted.files().getLast().content()).isEqualTo(MAIN);
        assertThatThrownBy(() -> parsed.files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ambiguityIsDeferredAndEveryProjectionFailureCanBeSuppressedOntoItsPredecessor() {
        String code = "// --- a/Common.java ---\nclass A {}\n"
                + "// --- b/Common.java ---\nclass B {}\n";
        SubmittedSources submitted = CompilationHelper.parseSources(code).submitted();
        assertThat(submitted.files()).hasSize(2);
        Throwable first = catchThrowable(submitted::metadata);
        Throwable retry = catchThrowable(submitted::metadata);
        assertThat(first).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
        assertThat(retry).isNotSameAs(first).hasMessage(first.getMessage());
        first.addSuppressed(retry);
        assertThat(first.getSuppressed()).containsExactly(retry);
    }

    @Test
    void listingPreservesExactEmptyAndFirstSuffixFallbackWithoutChangingBreakpointMatching() {
        SubmittedSources.File file = CompilationHelper.parseSources("// --- src/C.java ---\nclass C {}\n")
                .submitted().files().getFirst();
        Map<String, Set<Integer>> lines = new LinkedHashMap<>();
        lines.put("Other.java", Set.of(1));
        assertThat(file.listingLines(lines)).isEmpty();
        lines.put("pkg/OtherC.java", Set.of(2));
        lines.put("pkg/C.java", Set.of(3));
        assertThat(file.listingLines(lines)).containsExactly(2);
        lines.put(file.logicalPath(), Set.of());
        assertThat(file.listingLines(lines)).containsExactly(2);
        Set<Integer> exact = Set.of(4);
        lines.put(file.logicalPath(), exact);
        assertThat(file.listingLines(lines)).isSameAs(exact);
        assertThat(file.listingLines(Map.of())).isEmpty();
    }

    @Test
    void preparedDescriptionsStillRenderUncompiledNoMainInputsWithOriginalFailureOrder() {
        String code = "// --- a/Common.java ---\nclass A {}\n"
                + "// --- b/Common.java ---\nclass B {}\n";
        SubmittedSources ambiguous = CompilationHelper.parseSources(code).submitted();
        ExecutionSnapshot invalid = new ExecutionSnapshot(null, List.of(), Map.of(), new byte[0], new byte[0]);
        ModernTraceSerializer modern = new ModernTraceSerializer(false, false, false);
        PyTutorSerializer pytutor = new PyTutorSerializer(false, false, false);
        assertThatThrownBy(() -> modern.createTrace(ambiguous, "", invalid))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pytutor.createTrace(ambiguous, "", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
        assertThatThrownBy(() -> modern.createTrace(ambiguous, "", List.of(invalid)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pytutor.createTrace(ambiguous, "", List.of(invalid)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> modern.createBreakpointsTrace(ambiguous, "", Map.of(1, invalid)))
                .isInstanceOf(NullPointerException.class);
        SubmittedSources noMain = CompilationHelper.parseSources("public class Uncompiled extends Missing {}")
                .submitted();
        assertThat(noMain.metadata().entryFile()).isEqualTo("Uncompiled.java");
        assertThat(pytutor.createTrace(noMain, null, List.of()).sources())
                .containsEntry("Uncompiled.java", noMain.code());
        assertThat(modern.createTrace(noMain, null, List.of()).sources())
                .containsEntry("Uncompiled.java", noMain.code());
    }

    @Test
    void parsedPreparationRetainsItsSourceIdentityAndCleansOwnedDependenciesOnFailure(@TempDir Path root)
            throws Exception {
        var parsed = CompilationHelper.parseSources("public class Main { public static void main(String[] a) {} }");
        try (var prepared = SourcePreparation.prepareParsed(parsed, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.empty())) {
            assertThat(prepared.submitted()).isSameAs(parsed.submitted());
        }
        Path library = Files.createDirectory(root.resolve("library"));
        TraceClassPath dependencies = TraceClassPath.builder().addPath(library).copy(true).build();
        var bad = CompilationHelper.parseSources("public class Main extends Missing {} ");
        assertThatThrownBy(() -> SourcePreparation.prepareParsed(bad, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, dependencies))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Compilation");
        assertThat(library).exists();
    }

}
