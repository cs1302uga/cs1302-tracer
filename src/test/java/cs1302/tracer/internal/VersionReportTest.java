package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;
import java.util.Arrays;
import picocli.CommandLine.Help.Ansi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class VersionReportTest {

    private static String[] plain(String[] report) {
        return Arrays.stream(report).map(Ansi.OFF::string).toArray(String[]::new);
    }

    @Test
    void stylesTheReport() {
        String[] report = App.PropertiesVersionProvider.formatVersion(new Properties(), new Properties());
        String first = Ansi.ON.string(report[0]);
        assertThat(first).startsWith("\u001b[1mcode-tracer development (unknown)\u001b[")
                .doesNotContain("\u001b[36m", "\u001b[32m", "\u001b[2m");
        assertThat(Ansi.OFF.string(report[0])).isEqualTo("code-tracer development (unknown)");
    }

    @Test
    void formatsPackagedProvenanceAndRuntimeProperties() {
        Properties build = new Properties();
        build.setProperty("version", "4.2.0-SNAPSHOT");
        build.setProperty("git.commit", "caf1355923fcea4e8587a06c7bf1d008935b66e9");
        build.setProperty("git.dirty", "true");
        Properties runtime = new Properties();
        runtime.setProperty("os.name", "Mac OS X");
        runtime.setProperty("os.version", "26.7.1");
        runtime.setProperty("os.arch", "aarch64");
        runtime.setProperty("java.runtime.name", "OpenJDK Runtime Environment");
        runtime.setProperty("java.vendor.version", "Homebrew");
        runtime.setProperty("java.version", "25.0.4.1");
        runtime.setProperty("java.version.date", "2026-08-18");
        runtime.setProperty("java.runtime.version", "25.0.4.1");
        runtime.setProperty("java.vm.name", "OpenJDK 64-Bit Server VM");
        runtime.setProperty("java.vm.version", "25.0.4.1");
        runtime.setProperty("java.vm.info", "mixed mode, sharing");
        String[] styled = Arrays.stream(App.PropertiesVersionProvider.formatVersion(build, runtime))
                .map(Ansi.ON::string).toArray(String[]::new);
        assertThat(styled[0]).startsWith("\u001b[1mcode-tracer 4.2.0-SNAPSHOT ("
                + "caf1355923fcea4e8587a06c7bf1d008935b66e9-dirty)\u001b[");
        assertThat(Arrays.asList(styled).subList(1, 5))
                .allSatisfy(line -> assertThat(line).doesNotContain("\u001b", "@|"));
        assertThat(plain(App.PropertiesVersionProvider.formatVersion(build, runtime))).containsExactly(
                "code-tracer 4.2.0-SNAPSHOT (caf1355923fcea4e8587a06c7bf1d008935b66e9-dirty)",
                "macOS 26.7.1 aarch64",
                "openjdk 25.0.4.1 2026-08-18",
                "OpenJDK Runtime Environment Homebrew (build 25.0.4.1)",
                "OpenJDK 64-Bit Server VM Homebrew (build 25.0.4.1, mixed mode, sharing)");
    }

    @Test
    void missingMetadataUsesExplicitFallbacks() {
        assertThat(plain(App.PropertiesVersionProvider.formatVersion(new Properties(), new Properties()))).containsExactly(
                "code-tracer development (unknown)", "unknown unknown unknown", "java unknown unknown",
                "unknown unknown (build unknown)", "unknown unknown (build unknown, unknown)");
        Properties runtime = new Properties();
        runtime.setProperty("java.vendor", "Example Vendor");
        assertThat(plain(App.PropertiesVersionProvider.formatVersion(new Properties(), runtime))[3])
                .isEqualTo("unknown Example Vendor (build unknown)");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "trace", "batch-trace", "list-breakpoints", "show-licenses" })
    void everyVersionOptionReportsWithoutExecutingTheCommand(String subcommand) {
        for (String option : new String[] { "--version", "-V" }) {
            for (Ansi ansi : new Ansi[] { Ansi.ON, Ansi.OFF, Ansi.AUTO }) {
                CommandLine cli = new CommandLine(new App()).addSubcommand(new App.Trace())
                        .addSubcommand(new App.BatchTrace()).addSubcommand(new App.ListBreakpoints())
                        .addSubcommand(new App.ShowLicenses());
                cli.setColorScheme(CommandLine.Help.defaultColorScheme(ansi));
                StringWriter out = new StringWriter();
                StringWriter err = new StringWriter();
                cli.setOut(new PrintWriter(out));
                cli.setErr(new PrintWriter(err));
                String[] args = subcommand.isEmpty() ? new String[] { option } : new String[] { subcommand, option };
                assertThat(cli.execute(args)).isZero();
                String[] report = new App.PropertiesVersionProvider().getVersion();
                assertThat(out.toString().lines().toList())
                        .containsExactly(Arrays.stream(report).map(ansi::string).toArray(String[]::new));
                assertThat(out.toString().replaceAll("\\u001B\\[[0-9;]*m", "").lines().toList())
                        .containsExactly(plain(report));
                assertThat(err.toString()).isEmpty();
            }
        }
    }
}
