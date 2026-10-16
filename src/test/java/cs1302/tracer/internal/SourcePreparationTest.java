package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourcePreparationTest {
    @Test
    void preparedSourcesOwnCompilationUntilClosed() throws Exception {
        Path classes;
        try (var prepared = SourcePreparation.prepare(
                "public class Main { public static void main(String[] args) {} }", Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            classes = prepared.compilation().classPath();
            assertThat(classes.resolve("Main.class")).exists();
            assertThat(prepared.compilation().mainClass()).isEqualTo("Main");
            assertThat(prepared.units()).hasSize(1);
            assertThat(prepared.units().getFirst().getType(0).getNameAsString()).isEqualTo("Main");
        }
        assertThat(classes).doesNotExist();
    }
    @Test
    void neighborDiscoveryIncludesEvidenceWithoutCompilingUnusedSources(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("sample"));
        String source = "package sample; public class Main { public static void main(String[] args) {} }";
        Path input = Files.writeString(pkg.resolve("Main.java"), source);
        Files.writeString(pkg.resolve("Neighbor.java"), "package sample; class Neighbor {}");
        Files.write(pkg.resolve("Invalid.java"), new byte[] {(byte) 0xff});
        Files.writeString(pkg.resolve("notes.txt"), "not java");
        try (var prepared = SourcePreparation.prepare(source, Optional.of(input),
                SourcePreparation.Discovery.NEIGHBORS)) {
            assertThat(prepared.units()).extracting(unit -> unit.getType(0).getNameAsString())
                    .containsExactlyInAnyOrder("Main", "Neighbor");
            assertThat(prepared.compilation().classPath().resolve("sample/Neighbor.class")).doesNotExist();
        }
        try (var prepared = SourcePreparation.prepare(source, Optional.of(input),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            assertThat(prepared.units()).hasSize(1);
        }
    }
    @Test
    void compiledSourceFallbackResolvesOtherSubmittedFiles() throws Exception {
        String source = "// --- Main.java ---\n"
                + "public class Main extends Parent { public static void main(String[] args) {} }\n"
                + "// --- Parent.java ---\nclass Parent {}\n";
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.NEIGHBORS_OR_COMPILED)) {
            assertThat(prepared.units().getFirst().getType(0).asClassOrInterfaceDeclaration()
                    .getExtendedTypes().get(0).resolve().describe()).isEqualTo("Parent");
        }
    }
}
