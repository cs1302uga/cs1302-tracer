package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilationBoundaryTest {
    @Test
    void streamedDelimitersPreserveSourceLineNumbersAcrossLineEndings() {
        for (String newline : List.of("\n", "\r\n")) {
            String content = "\npublic class Main {}".replace("\n", newline);
            var files = CompilationHelper.parseMultiFileStream("// --- Main.java ---" + newline + content);
            assertThat(files).hasSize(1);
            assertThat(files.getFirst().content()).isEqualTo(content);
            assertThat(files.getFirst().ast().getType(0).getBegin().orElseThrow().line).isEqualTo(2);
        }
        assertThat(CompilationHelper.parseMultiFileStream("// --- Empty.java ---").getFirst().content()).isEmpty();
    }

    @Test
    void emptyInputsAreRejectedAndImportsAreDeduplicated() {
        assertThatThrownBy(() -> CompilationHelper.findEntryPoint(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompilationHelper.combineCompilationUnits(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompilationHelper.findEntryPoint(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompilationHelper.combineCompilationUnits(List.of())).isInstanceOf(IllegalArgumentException.class);
        var sources = CompilationHelper.parseMultiFileStream("""
                // --- One.java ---
                import java.util.List;
                public class One {}
                // --- Two.java ---
                import java.util.List;
                import java.util.Map;
                public class Two {}
                """);
        var combined = CompilationHelper.combineCompilationUnits(sources);
        assertThat(combined.getImports()).extracting(imp -> imp.getNameAsString())
                .containsExactly("java.util.List", "java.util.Map");
        assertThat(combined.getTypes()).hasSize(2);
    }

    @Test
    void sourceRootsRequireMatchingExistingPackageDirectories(@TempDir Path directory) {
        var cu = StaticJavaParser.parse("package missing; class C {}");
        assertThat(CompilationHelper.findSourceRoot(cu, Optional.of(Path.of("/")))).isEmpty();
        assertThat(CompilationHelper.findSourceRoot(cu, Optional.of(directory.resolve("missing/C.java")))).isEmpty();
        assertThat(CompilationHelper.findSourceRoot(StaticJavaParser.parse("package a.b; class C {}"),
                Optional.of(Path.of("/b/C.java")))).isEmpty();
        assertThat(CompilationHelper.findSourceRoot(StaticJavaParser.parse("package cs1302.tracer; class C {}"), Optional.empty()))
                .contains(Path.of("src/main/java").toAbsolutePath().normalize());
    }

    @Test
    void mainDetectionRejectsWrongNameReturnAndParameters() {
        for (String signature : List.of("public void other()", "public static int main(String[] a)",
                "void main(int n)", "void main(String a)", "void main(String[] a, int n)",
                "public static void main(String[] a, String... b)")) {
            var method = StaticJavaParser.parseMethodDeclaration(signature + " { throw new RuntimeException(); }");
            assertThat(CompilationHelper.isMainMethod(method)).as(signature).isFalse();
        }
        for (String signature : List.of("public void main()", "void main(String... args)",
                "void main(java.lang.String[] args)")) {
            assertThat(CompilationHelper.isMainMethod(StaticJavaParser.parseMethodDeclaration(signature + " {}")))
                    .as(signature).isTrue();
        }
        assertThat(CompilationHelper.detectPreviewUsage(StaticJavaParser.parse("class C { public void main() {} }")))
                .isTrue();
    }

    @Test
    void absentDependencyRootDoesNotPreventSelfContainedCompilation(@TempDir Path directory) throws Exception {
        try (var compiled = CompilationHelper.compile("public class C { public static void main(String[] a) {} }",
                Optional.of(directory.resolve("absent")))) {
            assertThat(compiled.compiledClassNames()).contains("C");
        }
        var method = CompilationHelper.class.getDeclaredMethod("writeSourceFiles", Path.class, List.class);
        method.setAccessible(true);
        var invalid = new CompilationHelper.SourceFile("C.txt", "", StaticJavaParser.parse("class C {}"));
        assertThatThrownBy(() -> method.invoke(null, directory, List.of(invalid)))
                .cause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid or duplicate");
    }

    @Test
    void cleanupFailureIsReportedWithoutMaskingCompilationFailure(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("C.java"), "class C {}");
        Path failingPath = FaultPaths.refusingDeletion(directory);
        var original = new IllegalArgumentException("compilation failed");
        CompilationHelper.cleanupAfterFailure(failingPath, original);
        assertThat(original.getSuppressed()).singleElement().isInstanceOf(AccessDeniedException.class);
        var compiled = new CompilationHelper.CompilationResult(failingPath, Set.of(), "C");
        assertThatThrownBy(compiled::close).isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(directory.resolve("C.java")).exists();
    }
}
