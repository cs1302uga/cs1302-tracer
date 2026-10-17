package cs1302.tracer.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cs1302.tracer.api.TraceClassPath;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Native CLI syntax preserves ordered selections and never accesses the filesystem. */
class ClassPathOptionsTest {

    @Test
    void omissionDoesNotImportAnAmbientClassPath() {
        TraceClassPath configuration = new ClassPathOptions().resolve();
        assertThat(configuration.entries()).isEmpty();
        assertThat(configuration.copy()).isFalse();
        assertThat(configuration.followLinks()).isFalse();
    }

    @Test
    void aliasesAppendAndListsPreserveEmptyComponents() {
        ClassPathOptions options = new ClassPathOptions();
        new CommandLine(options).parseArgs("--class-path", "first" + File.pathSeparator + "second",
                "-classpath", "", "-cp", File.pathSeparator + "last" + File.pathSeparator,
                "--copy-class-path", "--follow-class-path-links");
        TraceClassPath configuration = options.resolve();
        assertThat(configuration.entries()).containsExactly(
                new TraceClassPath.PathEntry(Path.of("first")), new TraceClassPath.PathEntry(Path.of("second")),
                new TraceClassPath.PathEntry(Path.of("")), new TraceClassPath.PathEntry(Path.of("")),
                new TraceClassPath.PathEntry(Path.of("last")), new TraceClassPath.PathEntry(Path.of("")));
        assertThat(configuration.copy()).isTrue();
        assertThat(configuration.followLinks()).isTrue();
    }

    @Test
    void nativeWildcardAndRecursivePatternHaveDifferentJarRules() {
        ClassPathOptions options = new ClassPathOptions();
        new CommandLine(options).parseArgs("-cp", "lib/*", "--class-path", "../deps/**/*.jar",
                "-classpath", "*.zip", "-cp", "/absolute/path with spaces/*.JAR");
        assertThat(options.resolve().entries()).containsExactly(
                new TraceClassPath.GlobEntry(Path.of("lib"), "*.{jar,JAR}"),
                new TraceClassPath.GlobEntry(Path.of("../deps"), "**/*.jar"),
                new TraceClassPath.GlobEntry(Path.of(""), "*.zip"),
                new TraceClassPath.GlobEntry(Path.of("/absolute/path with spaces"), "*.JAR"));
    }

    @Test
    void patternsKeepBraceAlternativesAndEscapesUnderTheirLiteralRoot() {
        TraceClassPath.Builder builder = TraceClassPath.builder();
        ClassPathOptions.addSelection(builder, "relative/{one,two}/[ab]?.jar", '/');
        ClassPathOptions.addSelection(builder, "/*.jar", '/');
        assertThat(builder.build().entries()).containsExactly(
                new TraceClassPath.GlobEntry(Path.of("relative"), "{one,two}/[ab]?.jar"),
                new TraceClassPath.GlobEntry(Path.of("/"), "*.jar"));
    }

    @Test
    void nativeWindowsPrefixesStayInPathsWhileGlobSeparatorsArePortable() {
        TraceClassPath.Builder builder = TraceClassPath.builder();
        ClassPathOptions.addSelection(builder, "C:\\library\\**\\*.jar", '\\');
        ClassPathOptions.addSelection(builder, "\\\\server\\share\\*", '\\');
        ClassPathOptions.addSelection(builder, "C:/library/**/*.jar", '\\');
        ClassPathOptions.addSelection(builder, "C:*.jar", '\\');
        ClassPathOptions.addSelection(builder, "ab*.jar", '\\');
        ClassPathOptions.addSelection(builder, "*.jar", '\\');
        assertThat(builder.build().entries()).containsExactly(
                new TraceClassPath.GlobEntry(Path.of("C:\\library\\"), "**/*.jar"),
                new TraceClassPath.GlobEntry(Path.of("\\\\server\\share\\"), "*.{jar,JAR}"),
                new TraceClassPath.GlobEntry(Path.of("C:/library/"), "**/*.jar"),
                new TraceClassPath.GlobEntry(Path.of("C:"), "*.jar"),
                new TraceClassPath.GlobEntry(Path.of(""), "ab*.jar"),
                new TraceClassPath.GlobEntry(Path.of(""), "*.jar"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "library-*.jar", "library-?.jar", "library-[ab].jar", "library-{one,two}.jar" })
    void driveRelativePatternsPreserveTheDriveBeforeLiteralStems(String glob) {
        TraceClassPath.Builder builder = TraceClassPath.builder();
        ClassPathOptions.addSelection(builder, "C:" + glob, '\\');
        assertThat(builder.build().entries()).containsExactly(new TraceClassPath.GlobEntry(Path.of("C:"), glob));
    }

    @Test
    void malformedPatternsFailBeforeFilesystemSelection() {
        ClassPathOptions options = new ClassPathOptions();
        new CommandLine(options).parseArgs("-cp", "nonexistent/[bad");
        assertThatThrownBy(options::resolve).isInstanceOf(IllegalArgumentException.class);
    }

} // ClassPathOptionsTest
