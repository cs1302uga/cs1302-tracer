package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SourceBundleTest {

    @Test
    void requiresAFileButAllowsExplicitlyNamedEmptyOrInvalidJava() {
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one file");
        SourceBundle emptyFile = builder.addSource("Empty.java", "").build();
        assertThat(emptyFile.source()).isEqualTo("// --- Empty.java ---\n");
        SourceBundle invalidFile = builder.addSource("Broken.java", "this is not Java").build();
        assertThat(invalidFile.source())
                .isEqualTo("// --- Empty.java ---\n// --- Broken.java ---\nthis is not Java");
        assertThat(emptyFile.source()).isEqualTo("// --- Empty.java ---\n");
    }

    @ParameterizedTest
    @MethodSource("sourceEndings")
    void insertsOnlyTheNecessarySeparatorAndPreservesFinalText(String original, String separator) {
        SourceBundle bundle = SourceBundle.builder()
                .addSource("First.java", original)
                .addSource("Last.java", "final text without a newline")
                .build();
        assertThat(bundle.source()).isEqualTo("// --- First.java ---\n" + original + separator
                + "// --- Last.java ---\nfinal text without a newline");
    }

    private static Stream<Arguments> sourceEndings() {
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("text", "\n"),
                Arguments.of("text\n", ""),
                Arguments.of("text\r", ""),
                Arguments.of("text\r\n", ""),
                Arguments.of("text\u0085", ""),
                Arguments.of("text\u2028", ""),
                Arguments.of("text\u2029", ""),
                Arguments.of("text\n\n", ""));
    }

    @Test
    void retainsBomUnicodeAndOriginalLineEndingsInAllTransports() throws Exception {
        String original = "\uFEFF// café \uD83D\uDE03\r\nclass Example {}\r";
        SourceBundle bundle = SourceBundle.builder().addSource("café\\Example.java", original).build();
        String expected = "// --- café/Example.java ---\n" + original;
        TrackingOutput output = new TrackingOutput();
        bundle.writeUtf8(output);

        assertThat(bundle.source()).isEqualTo(expected);
        assertThat(output.toByteArray()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(output.flushed).isFalse();
        assertThat(output.closed).isFalse();
        try (InputStream first = bundle.openStream(); InputStream second = bundle.openStream()) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.read()).isEqualTo('/');
            assertThat(second.readAllBytes()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
            assertThat(first.readAllBytes()).containsExactly(expected.substring(1).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(bundle.openStream().readAllBytes()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void hostileOutputCannotMutateTheBundleThroughWritesOrStreamTransfer() throws Exception {
        SourceBundle bundle = SourceBundle.builder().addSource("Main.java", "class Main {}").build();
        String expected = bundle.source();
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        MutatingOutput output = new MutatingOutput();

        bundle.writeUtf8(output);
        assertThat(output.retained).isNotNull();
        Arrays.fill(output.retained, (byte) '?');
        assertThat(bundle.source()).isEqualTo(expected);
        assertThat(bundle.openStream().readAllBytes()).containsExactly(expectedBytes);

        try (InputStream input = bundle.openStream()) {
            assertThat(input.transferTo(output)).isEqualTo(expectedBytes.length);
        }
        Arrays.fill(output.retained, (byte) '#');
        ByteArrayOutputStream subsequent = new ByteArrayOutputStream();
        bundle.writeUtf8(subsequent);
        assertThat(subsequent.toByteArray()).containsExactly(expectedBytes);
        assertThat(bundle.openStream().readAllBytes()).containsExactly(expectedBytes);
    }

    @Test
    void propagatesOutputFailureWithoutChangingCapturedSource() throws Exception {
        SourceBundle bundle = SourceBundle.builder().addSource("Main.java", "class Main {}").build();
        String expected = bundle.source();
        IOException failure = new IOException("destination failed");
        OutputStream destination = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw failure;
            }
        };
        assertThatThrownBy(() -> bundle.writeUtf8(destination)).isSameAs(failure);
        assertThat(bundle.source()).isEqualTo(expected);
        assertThat(bundle.openStream().readAllBytes()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> bundle.writeUtf8(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildsAndCopyBuildersKeepIndependentSnapshotsInInsertionOrder() {
        SourceBundle.Builder builder = SourceBundle.builder().addSource("Z.java", "Z");
        SourceBundle first = builder.build();
        SourceBundle repeated = builder.build();
        SourceBundle second = builder.addSource("A.java", "A").build();
        SourceBundle.Builder copy = first.toBuilder();
        SourceBundle third = copy.addSource("M.java", "M").build();

        assertThat(repeated).isNotSameAs(first);
        assertThat(repeated.source()).isEqualTo(first.source());
        assertThat(first.source()).isEqualTo("// --- Z.java ---\nZ");
        assertThat(second.source()).isEqualTo("// --- Z.java ---\nZ\n// --- A.java ---\nA");
        assertThat(third.source()).isEqualTo("// --- Z.java ---\nZ\n// --- M.java ---\nM");
        assertThat(second.toBuilder().build().source()).isEqualTo(second.source());
        assertThatThrownBy(() -> copy.addSource("Z.java", "replacement"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Duplicate source path: Z.java");
        assertThat(copy.build().source()).isEqualTo(third.source());
    }

    @Test
    void copyBuilderUsesOriginalTextRatherThanPreviouslyAddedFraming() {
        SourceBundle original = SourceBundle.builder().addSource("A.java", "A")
                .addSource("Empty.java", "").build();
        SourceBundle extended = original.toBuilder().addSource("B.java", "B").build();
        assertThat(extended.source()).isEqualTo("// --- A.java ---\nA\n// --- Empty.java ---\n"
                + "// --- B.java ---\nB");
        assertThat(original.source()).isEqualTo("// --- A.java ---\nA\n// --- Empty.java ---\n");
    }

    @Test
    void capturesExplicitAndInferredFilesAtAdditionBeforeMutationOrDeletion(@TempDir Path directory)
            throws Exception {
        Path explicit = directory.resolve("not-a-java-name.txt");
        Path inferred = directory.resolve("another-name.txt");
        String explicitText = "\uFEFFcaptured café\r\n";
        String inferredText = "package example; class Hidden {}";
        Files.writeString(explicit, explicitText, StandardCharsets.UTF_8);
        Files.writeString(inferred, inferredText, StandardCharsets.UTF_8);
        SourceBundle.Builder builder = SourceBundle.builder().addFile("given\\Main.java", explicit)
                .addInferredFile(inferred);
        Files.writeString(explicit, "changed after add", StandardCharsets.UTF_8);
        Files.delete(inferred);
        String expected = "// --- given/Main.java ---\n" + explicitText
                + "// --- example/Hidden.java ---\n" + inferredText;

        SourceBundle bundle = builder.build();
        Files.delete(explicit);
        assertThat(bundle.source()).isEqualTo(expected);
        assertThat(builder.build().source()).isEqualTo(expected);
        assertThat(bundle.toBuilder().build().source()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("inferredSources")
    void infersNamesFromPublicOrSoleTopLevelTypes(String source, String path) {
        SourceBundle bundle = SourceBundle.builder().addInferredSource(source).build();
        assertThat(bundle.source()).isEqualTo("// --- " + path + " ---\n" + source);
    }

    private static Stream<Arguments> inferredSources() {
        return Stream.of(
                Arguments.of("package a.b; class Helper {} public class Main {}", "a/b/Main.java"),
                Arguments.of("class Hidden {}", "Hidden.java"),
                Arguments.of("interface Contract {}", "Contract.java"),
                Arguments.of("enum Shade { LIGHT }", "Shade.java"),
                Arguments.of("record Point(int x) {}", "Point.java"),
                Arguments.of("@interface Marker {}", "Marker.java"),
                Arguments.of("class Outer { public class Inner {} }", "Outer.java"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "// no type", "class A {} class B {}", "public class A {} public class B {}",
            "public class Broken {", "module example {}"})
    void rejectsUnparseableOrAmbiguousInferenceWithoutChangingTheBuilder(String source) {
        SourceBundle.Builder builder = SourceBundle.builder().addSource("Base.java", "base");
        assertThatThrownBy(() -> builder.addInferredSource(source)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot infer source path");
        assertThat(builder.build().source()).isEqualTo("// --- Base.java ---\nbase");
    }

    @Test
    void rejectsDuplicatesAfterSlashNormalizationWithoutReplacingText(@TempDir Path directory) {
        SourceBundle.Builder builder = SourceBundle.builder().addSource("example\\Main.java", "original");
        assertThatThrownBy(() -> builder.addSource("example/Main.java", "replacement"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate source path: example/Main.java");
        assertThatThrownBy(() -> builder.addInferredSource("package example; public class Main {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate source path: example/Main.java");
        assertThatThrownBy(() -> builder.addFile("example\\Main.java", directory.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate source path: example/Main.java");
        assertThat(builder.build().source()).isEqualTo("// --- example/Main.java ---\noriginal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/Main.java", "\\Main.java", "\\\\server\\Main.java", "C:\\Main.java",
            "C:Main.java", "scheme:Main.java", "pkg//Main.java", "pkg/./Main.java", "pkg/../Main.java",
            "Main.jav", "Main.JAVA", "Main.java/", " Main.java", "Main.java ", "\u00a0Main.java",
            "Main.java\u00a0", "../Main.java", "./Main.java"})
    void rejectsInvalidPathsAtomically(String path) {
        SourceBundle.Builder builder = SourceBundle.builder().addSource("Base.java", "base");
        assertThatThrownBy(() -> builder.addSource(path, "source"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Invalid source path:");
        assertThat(builder.build().source()).isEqualTo("// --- Base.java ---\nbase");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 10, 11, 12, 13, 31, 127, 128, 133, 159, 0x2028, 0x2029})
    void rejectsControlsAndFramingCharactersWithinPaths(int codePoint) {
        String path = "pkg/Na" + (char) codePoint + "me.java";
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThatThrownBy(() -> builder.addSource(path, "source"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Invalid source path:");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0xd800, 0xdc00})
    void rejectsUnpairedSurrogatesInPathsAndSourceWithoutReservingEntries(int surrogate) {
        String malformed = Character.toString((char) surrogate);
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThatThrownBy(() -> builder.addSource("Bad" + malformed + ".java", "valid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unpaired UTF-16 surrogate");
        assertThatThrownBy(() -> builder.addSource("Main.java", malformed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Main.java");
        assertThatThrownBy(() -> builder.addInferredSource(malformed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inferred source");
        assertThat(builder.addSource("Main.java", "recovered").build().source())
                .isEqualTo("// --- Main.java ---\nrecovered");
    }

    @ParameterizedTest
    @ValueSource(strings = {"// --- Other.java ---", "//===Other.java===", "//---\t Other.java \t===\t",
            "//---Other.java---.java---"})
    void rejectsEveryRecognizedDelimiterShape(String delimiter) {
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThatThrownBy(() -> builder.addSource("Main.java", delimiter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source Main.java contains a file delimiter at line 1");
        assertThat(builder.addSource("Main.java", "recovered").build().source())
                .isEqualTo("// --- Main.java ---\nrecovered");
    }

    @ParameterizedTest
    @MethodSource("collisionPrefixes")
    void reportsCollisionLineNumbersUsingStreamTerminatorsAndSingleCrLfLines(String prefix, int line) {
        assertThatThrownBy(() -> SourceBundle.builder().addSource("pkg/Main.java",
                prefix + "// --- Other.java ---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source pkg/Main.java contains a file delimiter at line " + line);
    }

    private static Stream<Arguments> collisionPrefixes() {
        return Stream.of(
                Arguments.of("", 1),
                Arguments.of("text\n", 2),
                Arguments.of("text\r\n", 2),
                Arguments.of("text\r", 2),
                Arguments.of("text\u0085", 2),
                Arguments.of("text\u2028", 2),
                Arguments.of("text\u2029", 2),
                Arguments.of("x\rZ\n", 3),
                Arguments.of("x\r\n\r\n", 3),
                Arguments.of("x\n\n", 3),
                Arguments.of("x\r\r", 3));
    }

    @Test
    void rejectsDelimiterLinesInsideTextBlocksAndPreservesNonmatchingComments() {
        String textBlock = "class Main { String text = \"\"\"\n// --- Other.java ---\n\"\"\"; }";
        assertThatThrownBy(() -> SourceBundle.builder().addSource("Main.java", textBlock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 2");
        String source = " // --- Indented.java ---\n// --- MissingTail.java\n// --- NotJava.txt ---";
        assertThat(SourceBundle.builder().addSource("Main.java", source).build().source())
                .isEqualTo("// --- Main.java ---\n" + source);
    }

    @Test
    void collisionDiagnosticsUseInferredNamesOrInputIdentifiersWhenInferenceFails(@TempDir Path directory)
            throws Exception {
        String named = "package sample; public class Main {}\n// --- Other.java ---";
        assertThatThrownBy(() -> SourceBundle.builder().addInferredSource(named))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source sample/Main.java contains a file delimiter at line 2");
        String ambiguous = "class A {}\n// --- Other.java ---\nclass B {}";
        assertThatThrownBy(() -> SourceBundle.builder().addInferredSource(ambiguous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source inferred source contains a file delimiter at line 2");
        Path file = Files.writeString(directory.resolve("unknown.txt"),
                "not Java\n// --- Other.java ---", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SourceBundle.builder().addInferredFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source " + file + " contains a file delimiter at line 2");
    }

    @Test
    void fileFailuresAreImmediateAndLeaveTheBuilderReusable(@TempDir Path directory) throws Exception {
        SourceBundle.Builder builder = SourceBundle.builder().addSource("Base.java", "base");
        Path missing = directory.resolve("missing.java");
        Path malformed = Files.write(directory.resolve("malformed.java"), new byte[] {(byte) 0xc3, 0x28});
        Path collision = Files.writeString(directory.resolve("collision.txt"), "// --- Other.java ---",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> builder.addFile("Main.java", missing)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> builder.addInferredFile(missing)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> builder.addFile("Main.java", malformed)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> builder.addInferredFile(malformed)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> builder.addFile("Main.java", collision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source Main.java contains a file delimiter at line 1");
        assertThatThrownBy(() -> builder.addFile("../Main.java", missing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build().source()).isEqualTo("// --- Base.java ---\nbase");
        assertThat(builder.addSource("Main.java", "recovered").build().source())
                .isEqualTo("// --- Base.java ---\nbase\n// --- Main.java ---\nrecovered");
    }

    @Test
    void rejectsNullInputsWithoutAddingAFile(@TempDir Path directory) {
        SourceBundle.Builder builder = SourceBundle.builder();
        assertThatThrownBy(() -> builder.addSource(null, "source")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addSource("Main.java", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addInferredSource(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addFile(null, directory.resolve("missing")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addFile("Main.java", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addInferredFile(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {

        private boolean flushed;
        private boolean closed;

        @Override
        public void flush() {
            this.flushed = true;
        }

        @Override
        public void close() {
            this.closed = true;
        }

    }

    private static final class MutatingOutput extends OutputStream {

        private byte[] retained;

        @Override
        public void write(int value) {
            throw new AssertionError("Expected a bulk write");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            this.retained = bytes;
            Arrays.fill(bytes, offset, offset + length, (byte) '!');
        }

    }

}
