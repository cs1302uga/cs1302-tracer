package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Observable capture, naming, and transaction contracts for glob additions. */
class SourceBundleFilesTest {

    @Test
    void explicitGroupAppendsInAliasOrderAndCapturesImmediately(@TempDir Path root) throws Exception {
        write(root, "demo/Z.java", "class Z {}\r\n");
        write(root, "demo/deep/M.java", "class M {}");
        write(root, "demo/A.java", "class A {}\n");
        SourceBundle original = SourceBundle.builder().addSource("First.java", "first").build();
        SourceBundle.Builder builder = original.toBuilder();
        assertThat(builder.addFiles(root, "demo/**/*.java")).isSameAs(builder);
        Files.delete(root.resolve("demo/A.java"));
        Files.writeString(root.resolve("demo/Z.java"), "changed");
        assertThat(builder.build().source()).isEqualTo("""
                // --- First.java ---
                first
                // --- demo/A.java ---
                class A {}
                // --- demo/Z.java ---
                class Z {}\r
                // --- demo/deep/M.java ---
                class M {}""");
        assertThat(original.source()).isEqualTo("// --- First.java ---\nfirst");
    }

    @Test
    void inferenceUsesCapturedJavaButOrdersByAliasPath(@TempDir Path root) throws Exception {
        write(root, "b.template", "package names; public class A {}");
        write(root, "a.template", "package names; class Z {}");
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThat(builder.addInferredFiles(root, "*.template")).isSameAs(builder);
        Files.delete(root.resolve("a.template"));
        Files.writeString(root.resolve("b.template"), "broken");
        assertThat(builder.build().source()).isEqualTo("""
                // --- names/Z.java ---
                package names; class Z {}
                // --- names/A.java ---
                package names; public class A {}""");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicatesAgainstExistingEntriesRollBackEarlierMatches(boolean infer, @TempDir Path root)
            throws Exception {
        write(root, "A.java", "class A {}");
        write(root, "Z.java", "class Z {}");
        SourceBundle.Builder builder = SourceBundle.builder().addSource("Z.java", "original");
        assertThatThrownBy(() -> add(builder, root, "*.java", infer))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Z.java");
        assertThat(builder.build().source()).isEqualTo("// --- Z.java ---\noriginal");
        builder.addSource("A.java", "after failure");
        assertThat(builder.build().source()).contains("after failure");
    }

    @Test
    void repeatedInferredNamesRollBackWholeGroup(@TempDir Path root) throws Exception {
        write(root, "one.txt", "package p; class Same {}");
        write(root, "two.txt", "package p; class Same {}");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addInferredFiles(root, "*.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("p/Same.java");
        assertUnchanged(builder);
    }

    @Test
    void normalizedExplicitPathsCanCollideWithinAGroup(@TempDir Path root) throws Exception {
        assumeFalse(root.getFileSystem().getSeparator().equals("\\"));
        write(root, "A.java", "class A {}");
        write(root, "p/Same.java", "class Same {}");
        write(root, "p\\Same.java", "class Different {}");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addFiles(root, "**/*.java"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("p/Same.java");
        assertUnchanged(builder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedUtf8AfterValidMatchRollsBack(boolean infer, @TempDir Path root) throws Exception {
        write(root, "A.java", "class A {}");
        Files.write(root.resolve("Z.java"), new byte[]{(byte) 0xc3, (byte) 0x28});
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> add(builder, root, "*.java", infer)).isInstanceOf(IOException.class);
        assertUnchanged(builder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delimiterCollisionAfterValidMatchRollsBack(boolean infer, @TempDir Path root) throws Exception {
        write(root, "A.java", "class A {}");
        write(root, "Z.java", "class Z {}\n// --- hidden.java ---\n");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> add(builder, root, "*.java", infer))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Z.java")
                .hasMessageContaining("line 2");
        assertUnchanged(builder);
    }

    @Test
    void invalidInferredJavaRollsBackButExplicitJavaIsNotParsed(@TempDir Path root) throws Exception {
        write(root, "A.java", "class A {}");
        write(root, "Z.java", "not Java");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addInferredFiles(root, "*.java"))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(builder);
        builder.addFiles(root, "*.java");
        assertThat(builder.build().source()).contains("// --- Z.java ---\nnot Java");
    }

    @Test
    void explicitInvalidExtensionFailsInsteadOfFilteringMatch(@TempDir Path root) throws Exception {
        write(root, "A.java", "class A {}");
        write(root, "Z.txt", "class Z {}");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addFiles(root, "*"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Z.txt");
        assertUnchanged(builder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noMatchesAndBadPatternsLeaveBuilderUnchanged(boolean infer, @TempDir Path root) {
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> add(builder, root, "*.java", infer))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(root.toString())
                .hasMessageContaining("*.java");
        assertThatThrownBy(() -> add(builder, root.resolve("missing"), "[", infer))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> add(builder, null, "*.java", infer)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> add(builder, root, null, infer)).isInstanceOf(NullPointerException.class);
        assertUnchanged(builder);
    }

    @Test
    void explicitAliasesAreCapturedWhileInferenceRejectsTheirDuplicateName(@TempDir Path root) throws Exception {
        Path actual = write(root, "Main.java", "class Main {}");
        Files.createSymbolicLink(root.resolve("Alias.java"), actual);
        assertThat(SourceBundle.builder().addFiles(root, "*.java").build().source())
                .isEqualTo("// --- Main.java ---\nclass Main {}");
        assertThat(SourceBundle.builder().addFiles(root, "*.java", true).build().source())
                .isEqualTo("// --- Alias.java ---\nclass Main {}\n// --- Main.java ---\nclass Main {}");
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addInferredFiles(root, "*.java", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Main.java");
        assertUnchanged(builder);
    }

    @Test
    void externalLinksCanBeOptedIntoAndRootLinkIsAlwaysAccepted(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path actual = write(directory, "outside/Other.txt", "package p; class Other {}");
        Files.createSymbolicLink(root.resolve("Linked.txt"), actual);
        Path rootAlias = Files.createSymbolicLink(directory.resolve("root-alias"), root);
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> builder.addInferredFiles(rootAlias, "*.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(builder);
        assertThat(builder.addInferredFiles(rootAlias, "*.txt", true)).isSameAs(builder);
        assertThat(builder.build().source()).contains("// --- p/Other.java ---\npackage p; class Other {}");
        write(root, "Main.java", "");
        assertThat(SourceBundle.builder().addFiles(rootAlias, "*.java").build().source())
                .isEqualTo("// --- Main.java ---\n");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedBrokenLinkAbortsOptedInTraversal(boolean infer, @TempDir Path root) throws Exception {
        write(root, "A.java", "class A {}");
        Files.createSymbolicLink(root.resolve("broken.txt"), root.resolve("missing.txt"));
        SourceBundle.Builder builder = seeded();
        assertThatThrownBy(() -> {
            if (infer) {
                builder.addInferredFiles(root, "*.java", true);
            } else {
                builder.addFiles(root, "*.java", true);
            }
        }).isInstanceOf(IOException.class);
        assertUnchanged(builder);
    }

    private static void add(SourceBundle.Builder builder, Path root, String pattern, boolean infer) throws IOException {
        if (infer) {
            builder.addInferredFiles(root, pattern);
        } else {
            builder.addFiles(root, pattern);
        }
    }

    private static SourceBundle.Builder seeded() {
        return SourceBundle.builder().addSource("Seed.java", "seed");
    }

    private static void assertUnchanged(SourceBundle.Builder builder) {
        assertThat(builder.build().source()).isEqualTo("// --- Seed.java ---\nseed");
    }

    private static Path write(Path root, String name, String source) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }

}
